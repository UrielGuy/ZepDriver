package com.zepdriver.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.UUID

/**
 * Meshtastic BLE client transport: scan, bond, connect, and move ToRadio /
 * FromRadio protobuf bytes. GATT operations are serialized through a queue
 * (one outstanding at a time) and everything runs on one handler thread.
 * Reconnects with backoff while a connection is wanted.
 */
@SuppressLint("MissingPermission") // checked in hasPermissions() before any Bluetooth call
internal class BleTransport(context: Context, private val listener: Listener) {

    interface Listener {
        fun onLinkState(state: LinkState, deviceName: String?, detail: String?)
        /** Notifications are on; the node is ready for ToRadio packets. */
        fun onReady(deviceName: String?)
        fun onFromRadio(bytes: ByteArray)
        fun onLog(line: String)
    }

    enum class LinkState { IDLE, SCANNING, BONDING, CONNECTING, READY, ERROR }

    private val app = context.applicationContext
    private val thread = HandlerThread("zep-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val prefs = app.getSharedPreferences("mesh", Context.MODE_PRIVATE)

    private var wanted = false
    private var attempt = 0
    private var scanning = false
    private val found = LinkedHashMap<String, ScanResult>()
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null
    private var ready = false
    private var bondWaiting = false

    // --- GATT operation queue --------------------------------------------------

    private sealed class Op {
        class Write(val bytes: ByteArray) : Op()
        object Read : Op()
        object EnableNotify : Op()
        class Mtu(val mtu: Int) : Op()
        object Discover : Op()
        class Run(val block: () -> Unit) : Op()
    }

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var draining = false
    private var drainAgain = false

    private val opTimeout = Runnable {
        listener.onLog("Bluetooth operation timed out")
        dropLink("Radio stopped responding")
    }
    private val scanTimeout = Runnable { onScanWindowEnd() }
    private val connectTimeout = Runnable { if (!ready) dropLink("Connection timed out") }
    private val bondTimeout = Runnable { if (bondWaiting) { bondWaiting = false; device?.let { connectGatt(it) } } }
    private val reconnect = Runnable { if (wanted && gatt == null && !scanning) startScan() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val dev = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            handler.post { onBondState(dev, state) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            null, handler, ContextCompat.RECEIVER_EXPORTED,
        )
    }

    // --- public API (any thread) -------------------------------------------------

    fun connect() {
        handler.post {
            wanted = true
            attempt = 0
            if (gatt == null && !scanning) startScan()
        }
    }

    fun disconnect() {
        handler.post {
            wanted = false
            handler.removeCallbacks(reconnect)
            stopScan()
            closeGatt()
            listener.onLinkState(LinkState.IDLE, null, null)
        }
    }

    fun send(bytes: ByteArray) {
        handler.post {
            if (ready) enqueue(Op.Write(bytes)) else listener.onLog("Not connected; packet dropped")
        }
    }

    /** Sends [packets] (e.g. provisioning), then drops the link and reconnects after [delayMs] (node reboots). */
    fun sendThenReconnect(packets: List<ByteArray>, delayMs: Long) {
        handler.post {
            if (ready) {
                packets.forEach { enqueue(Op.Write(it)) }
                enqueue(Op.Run {
                    closeGatt()
                    listener.onLinkState(LinkState.CONNECTING, null, "Radio restarting…")
                    handler.removeCallbacks(reconnect)
                    if (wanted) handler.postDelayed(reconnect, delayMs)
                })
            }
        }
    }

    val isReady: Boolean get() = ready

    fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    // --- scanning ---------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onFound(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { results.forEach { onFound(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                fail("Bluetooth scan failed ($errorCode)")
            }
        }
    }

    private fun startScan() {
        if (!hasPermissions()) return fail("Bluetooth permission needed")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return fail("This phone has no Bluetooth")
        if (!adapter.isEnabled) return fail("Bluetooth is off")
        val scanner = adapter.bluetoothLeScanner ?: return fail("Bluetooth is off")
        found.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            return fail("Bluetooth scan failed: ${e.message}")
        }
        scanning = true
        listener.onLinkState(LinkState.SCANNING, null, "Looking for your radio…")
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_MS)
    }

    private fun onFound(result: ScanResult) {
        if (!scanning) return
        val first = found.isEmpty()
        found[result.device.address] = result
        val preferred = prefs.getString(KEY_LAST_DEVICE, null)
        when {
            result.device.address == preferred -> onScanWindowEnd()
            // Give other radios a moment to show up, then take the strongest.
            first -> { handler.removeCallbacks(scanTimeout); handler.postDelayed(scanTimeout, PICK_WINDOW_MS) }
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            listener.onLog("stopScan: ${e.message}")
        }
    }

    private fun onScanWindowEnd() {
        if (!scanning) return
        stopScan()
        val preferred = prefs.getString(KEY_LAST_DEVICE, null)
        val pick = found.values.firstOrNull { it.device.address == preferred } ?: found.values.maxByOrNull { it.rssi }
        if (pick == null) {
            listener.onLog("No ZepDriver radio found nearby")
            scheduleReconnect("No radio found")
            return
        }
        val dev = pick.device
        device = dev
        listener.onLog("Found ${dev.name ?: dev.address} (${pick.rssi} dBm)")
        if (dev.bondState == BluetoothDevice.BOND_NONE) {
            // Pairing first makes Android show its PIN dialog before GATT needs encryption.
            listener.onLinkState(LinkState.BONDING, dev.name, "Pairing — enter PIN ${ZepMeshPin.text} if asked (or the PIN on the radio's screen)")
            bondWaiting = dev.createBond()
            if (bondWaiting) handler.postDelayed(bondTimeout, BOND_TIMEOUT_MS) else connectGatt(dev)
        } else {
            connectGatt(dev)
        }
    }

    private fun onBondState(dev: BluetoothDevice?, state: Int) {
        if (dev == null || dev.address != device?.address) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                listener.onLog("Paired with ${dev.name ?: dev.address}")
                if (bondWaiting) {
                    bondWaiting = false
                    handler.removeCallbacks(bondTimeout)
                    connectGatt(dev)
                }
            }
            BluetoothDevice.BOND_NONE -> if (bondWaiting) {
                // Declined or failed; some radios work without pairing, so try anyway.
                listener.onLog("Pairing didn't complete; trying to connect anyway")
                bondWaiting = false
                handler.removeCallbacks(bondTimeout)
                connectGatt(dev)
            }
        }
    }

    // --- connection ---------------------------------------------------------------

    private fun connectGatt(dev: BluetoothDevice) {
        if (!wanted) return
        closeGatt()
        listener.onLinkState(LinkState.CONNECTING, dev.name, "Connecting to ${dev.name ?: dev.address}…")
        gatt = try {
            dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        } catch (e: Exception) {
            listener.onLog("connectGatt failed: ${e.message}")
            null
        }
        if (gatt == null) scheduleReconnect("Couldn't connect") else handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) { g.close(); return }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("Connected; setting up")
                enqueue(Op.Mtu(512))
                enqueue(Op.Discover)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                dropLink(if (ready) "Radio disconnected" else "Connection failed ($status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (current is Op.Mtu) complete()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (current is Op.Discover) complete()
            val service = g.getService(SERVICE_UUID)
            toRadio = service?.getCharacteristic(TO_RADIO_UUID)
            fromRadio = service?.getCharacteristic(FROM_RADIO_UUID)
            fromNum = service?.getCharacteristic(FROM_NUM_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || toRadio == null || fromRadio == null || fromNum == null) {
                wanted = false
                closeGatt()
                fail("That device isn't a Meshtastic radio")
                return
            }
            enqueue(Op.EnableNotify)
            enqueue(Op.Run {
                ready = true
                attempt = 0
                handler.removeCallbacks(connectTimeout)
                val dev = g.device
                prefs.edit().putString(KEY_LAST_DEVICE, dev.address).apply()
                listener.onLinkState(LinkState.READY, dev.name, null)
                listener.onReady(dev.name)
            })
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onLog("Enabling notifications failed ($status)")
            if (current is Op.EnableNotify) complete()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onLog("Write failed ($status)")
            if (current is Op.Write) complete()
            requestDrain()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled by the overload below
            onRead(characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == FROM_NUM_UUID) requestDrain()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == FROM_NUM_UUID) requestDrain()
        }
    }

    private fun onRead(value: ByteArray, status: Int) {
        if (current === Op.Read) complete()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // Usually insufficient authentication while Android is pairing; try again shortly.
            listener.onLog("Read failed ($status)")
            draining = false
            handler.postDelayed({ requestDrain() }, 2_000)
            return
        }
        if (value.isNotEmpty()) {
            listener.onFromRadio(value)
            enqueue(Op.Read)
        } else {
            draining = false
            if (drainAgain) {
                drainAgain = false
                requestDrain()
            }
        }
    }

    /** Reads fromRadio until it returns an empty value. */
    private fun requestDrain() {
        if (gatt == null || fromRadio == null) return
        if (draining) {
            drainAgain = true
            return
        }
        draining = true
        drainAgain = false
        enqueue(Op.Read)
    }

    private fun enqueue(op: Op) {
        queue.addLast(op)
        next()
    }

    private fun next() {
        while (current == null) {
            val op = queue.removeFirstOrNull() ?: return
            val g = gatt ?: run { queue.clear(); return }
            current = op
            val started = try {
                start(g, op)
            } catch (e: Exception) {
                listener.onLog("Bluetooth error: ${e.message}")
                false
            }
            if (op is Op.Run) {
                current = null
                continue
            }
            if (started) {
                handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
            } else {
                listener.onLog("Bluetooth operation could not start")
                current = null
                if (op === Op.Read) draining = false
            }
        }
    }

    private fun start(g: BluetoothGatt, op: Op): Boolean {
        return when (op) {
            is Op.Write -> {
                val ch = toRadio ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, op.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    legacyWrite(g, ch, op.bytes)
                }
            }
            Op.Read -> {
                val ch = fromRadio ?: return false
                g.readCharacteristic(ch)
            }
            Op.EnableNotify -> {
                val ch = fromNum ?: return false
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    legacyWriteDescriptor(g, cccd, value)
                }
            }
            is Op.Mtu -> g.requestMtu(op.mtu)
            Op.Discover -> g.discoverServices()
            is Op.Run -> {
                op.block()
                true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        ch.setValue(bytes)
        return g.writeCharacteristic(ch)
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        d.setValue(value)
        return g.writeDescriptor(d)
    }

    private fun complete() {
        handler.removeCallbacks(opTimeout)
        current = null
        next()
    }

    // --- teardown -----------------------------------------------------------------

    private fun closeGatt() {
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(bondTimeout)
        bondWaiting = false
        queue.clear()
        current = null
        draining = false
        drainAgain = false
        ready = false
        toRadio = null
        fromRadio = null
        fromNum = null
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                listener.onLog("close: ${e.message}")
            }
        }
        gatt = null
    }

    private fun dropLink(reason: String) {
        val wasReady = ready
        closeGatt()
        if (wasReady) listener.onLog(reason)
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (!wanted) {
            listener.onLinkState(LinkState.IDLE, null, reason)
            return
        }
        val delay = (2_000L shl attempt.coerceAtMost(5)).coerceAtMost(60_000L)
        attempt++
        listener.onLinkState(LinkState.CONNECTING, null, "$reason — retrying in ${delay / 1000} s")
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, delay)
    }

    private fun fail(message: String) {
        listener.onLog(message)
        listener.onLinkState(LinkState.ERROR, null, message)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val KEY_LAST_DEVICE = "lastBleDevice"
        private const val SCAN_MS = 15_000L
        private const val PICK_WINDOW_MS = 2_500L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val OP_TIMEOUT_MS = 10_000L
        private const val BOND_TIMEOUT_MS = 60_000L
    }
}

/** The fixed BLE PIN as typed in Android's pairing dialog. */
internal object ZepMeshPin {
    val text: String get() = com.zepdriver.core.mesh.ZepMesh.BLE_PIN.toString().padStart(6, '0')
}
