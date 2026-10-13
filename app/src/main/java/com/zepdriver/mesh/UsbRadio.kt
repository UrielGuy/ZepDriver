package com.zepdriver.mesh

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.zepdriver.core.flash.SerialPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * USB-serial radios plugged into the phone: detection (attach / detach
 * broadcasts), permission requests and opening ports as core [SerialPort]s.
 */
internal class UsbRadios(context: Context, private val onChanged: (present: Boolean) -> Unit) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)
    private val defaultProber = UsbSerialProber.getDefaultProber()
    /** ESP32-S3 native USB (Serial/JTAG) speaks CDC-ACM but isn't in the default table. */
    private val customProber = UsbSerialProber(
        UsbSerialProber.getDefaultProbeTable().addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
    )

    @Volatile private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            pendingPermission?.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // System broadcasts.
        ContextCompat.registerReceiver(app, attachReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // Our own PendingIntent, sent back by the USB service.
        ContextCompat.registerReceiver(
            app, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Re-scans attached devices and reports whether a radio is present. */
    fun refresh() {
        onChanged(findDriver() != null)
    }

    fun findDriver(): UsbSerialDriver? {
        val manager = usb ?: return null
        return manager.deviceList.values.firstNotNullOfOrNull { device ->
            defaultProber.probeDevice(device) ?: customProber.probeDevice(device)
        }
    }

    /** Asks the user for access to [device] if needed. Returns true once granted. */
    suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // The USB service fills in extras, so the PendingIntent must be mutable (and explicit) on 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        manager.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags))
        val granted = withTimeoutOrNull(90_000) { deferred.await() } ?: false
        pendingPermission = null
        return granted || manager.hasPermission(device)
    }

    /** Opens the driver's first port at [baud], 8N1. */
    fun open(driver: UsbSerialDriver, baud: Int): UsbSerialAdapter {
        val manager = usb ?: throw IOException("USB host not supported")
        val connection = manager.openDevice(driver.device) ?: throw IOException("Couldn't open the USB device")
        val port = driver.ports.firstOrNull() ?: run {
            connection.close()
            throw IOException("USB device has no serial port")
        }
        try {
            port.open(connection)
        } catch (e: IOException) {
            connection.close()
            throw e
        }
        return UsbSerialAdapter(port).also { it.setBaudRate(baud) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.zepdriver.USB_PERMISSION"
    }
}

/** Core [SerialPort] over a usb-serial-for-android port. Not thread-safe. */
internal class UsbSerialAdapter(private val port: UsbSerialPort) : SerialPort, AutoCloseable {
    private val chunk = ByteArray(16 * 1024)
    private var pending = ByteArray(0)
    private var pendingAt = 0

    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (buffer.isEmpty()) return 0
        if (pendingAt >= pending.size) {
            // Read into a full-size chunk: some drivers lose data with buffers smaller than a USB packet.
            val n = port.read(chunk, timeoutMs.coerceAtLeast(1))
            if (n <= 0) return 0
            pending = chunk.copyOf(n)
            pendingAt = 0
        }
        val n = minOf(buffer.size, pending.size - pendingAt)
        System.arraycopy(pending, pendingAt, buffer, 0, n)
        pendingAt += n
        return n
    }

    override fun setBaudRate(baud: Int) {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override fun setDtr(value: Boolean) {
        port.setDTR(value)
    }

    override fun setRts(value: Boolean) {
        port.setRTS(value)
    }

    /** Drops anything buffered but not yet read. */
    fun discardPending() {
        pendingAt = pending.size
    }

    override fun close() {
        try {
            port.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000
    }
}
