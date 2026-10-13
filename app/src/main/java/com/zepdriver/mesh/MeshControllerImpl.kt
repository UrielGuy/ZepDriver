package com.zepdriver.mesh

import android.content.Context
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.zepdriver.MeshConnection
import com.zepdriver.MeshController
import com.zepdriver.MeshUiState
import com.zepdriver.SettingsStore
import com.zepdriver.core.flash.EspFlasher
import com.zepdriver.core.flash.FirmwareCatalog
import com.zepdriver.core.flash.FlashImage
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.mesh.MeshEvent
import com.zepdriver.core.mesh.MeshProtocol
import com.zepdriver.core.mesh.MeshText
import com.zepdriver.core.mesh.StreamFraming
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.data.Net
import com.zepdriver.data.TripLogImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * [MeshController] for a Meshtastic radio: BLE link to the car's radio,
 * fleet positions and text messages on the shared ZepDriver channel, and USB
 * flashing / provisioning of a new board.
 *
 * Nothing works until the driver has set a car name (the radio is named after
 * it), in which case the connection reads [MeshConnection.NO_CAR_NAME].
 * Protocol state is confined to one thread ([meshThread]).
 */
class MeshControllerImpl(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) : MeshController {

    private val app = context.applicationContext
    private val meshThread = Executors.newSingleThreadExecutor { r -> Thread(r, "zep-mesh") }.asCoroutineDispatcher()
    private val store = MeshStore(app)

    private val _state = MutableStateFlow(MeshUiState())
    override val state: StateFlow<MeshUiState> = _state.asStateFlow()
    private val _fleet = MutableStateFlow<List<FleetVehicle>>(emptyList())
    override val fleet: StateFlow<List<FleetVehicle>> = _fleet.asStateFlow()
    private val _messages = MutableStateFlow<List<MeshText>>(emptyList())
    override val messages: StateFlow<List<MeshText>> = _messages.asStateFlow()

    // --- mesh-thread state ---
    private var protocol = MeshProtocol()
    private var myNode: Long? = null
    private val vehicles = HashMap<Long, FleetVehicle>()
    private var heartbeatJob: Job? = null
    private var saveJob: Job? = null
    private var lastProvisionAt = 0L
    private var lastFix: Fix? = null
    private var lastSentAt = 0L
    private var lastSentFix: Fix? = null
    private var lastSentMode: DrivingMode? = null

    // --- connection status (written on the mesh thread) ---
    @Volatile private var nameBlank = settings.profile.value.carName.isBlank()
    @Volatile private var bleConn = MeshConnection.DISCONNECTED
    /** Overrides [bleConn] while a USB job runs (FLASHING / PROVISIONING) or after it failed (ERROR). */
    @Volatile private var usbConn: MeshConnection? = null
    private var usbJob: Job? = null

    private val bleListener = object : BleTransport.Listener {
        override fun onLinkState(state: BleTransport.LinkState, deviceName: String?, detail: String?) {
            onMesh { linkState(state, deviceName, detail) }
        }

        override fun onReady(deviceName: String?) {
            onMesh { linkReady(deviceName) }
        }

        override fun onFromRadio(bytes: ByteArray) {
            onMesh {
                val events = try {
                    protocol.handleFromRadio(bytes)
                } catch (e: Exception) {
                    log("Undecodable packet: ${e.message}")
                    emptyList()
                }
                events.forEach { handleEvent(it) }
            }
        }

        override fun onLog(line: String) = log(line)
    }

    private val ble = BleTransport(app, bleListener)
    private val usb = UsbRadios(app) { present -> _state.update { it.copy(usbDevicePresent = present) } }

    /** Restores persisted fleet/messages and starts watching the car name and USB. */
    fun start() {
        onMesh {
            store.loadFleet().forEach { vehicles[it.nodeNum] = it }
            pruneFleet()
            _messages.value = store.loadMessages()
            publish()
        }
        scope.launch(meshThread) {
            settings.profile.map { it.carName.isBlank() }.distinctUntilChanged().collect { blank ->
                nameBlank = blank
                if (blank) {
                    ble.disconnect()
                    publish("Set a car name to join the mesh")
                } else {
                    publish(if (_state.value.connection == MeshConnection.NO_CAR_NAME) "" else null)
                }
            }
        }
        scope.launch(meshThread) {
            while (isActive) {
                delay(60_000)
                pruneFleet()
            }
        }
        scope.launch(Dispatchers.IO) { usb.refresh() }
    }

    /** Re-checks for a plugged-in USB radio (e.g. when the activity gets USB_DEVICE_ATTACHED). */
    fun refreshUsb() {
        scope.launch(Dispatchers.IO) { usb.refresh() }
    }

    // --- MeshController ------------------------------------------------------------

    override fun connectBluetooth() = onMesh {
        if (!carNameOk()) return@onMesh
        if (usbConn == MeshConnection.ERROR) usbConn = null
        if (!ble.hasPermissions()) {
            bleConn = MeshConnection.ERROR
            publish("Bluetooth permission needed")
            return@onMesh
        }
        bleConn = MeshConnection.SCANNING
        publish("Looking for your radio…")
        ble.connect()
    }

    override fun disconnect() = onMesh {
        ble.disconnect()
        stopHeartbeat()
        bleConn = MeshConnection.DISCONNECTED
        if (usbConn == MeshConnection.ERROR) usbConn = null
        publish("Disconnected")
    }

    override fun sendText(text: String) = onMesh {
        val name = carName() ?: return@onMesh
        val body = text.trim().truncateUtf8(MAX_TEXT_BYTES)
        if (body.isEmpty()) return@onMesh
        if (bleConn != MeshConnection.CONNECTED || !ble.isReady) {
            publish("Not connected — message not sent")
            return@onMesh
        }
        try {
            ble.send(protocol.textPacket(body))
        } catch (e: Exception) {
            log("Couldn't encode message: ${e.message}")
            return@onMesh
        }
        appendMessage(MeshText(myNode ?: 0L, name, body, System.currentTimeMillis(), mine = true))
    }

    override fun flashUsbRadio() = startUsbJob(flash = true)

    override fun provisionUsbRadio() = startUsbJob(flash = false)

    // --- position sharing (called by the drive engine for every fix) ----------------

    fun onFix(fix: Fix, mode: DrivingMode) = onMesh {
        lastFix = fix
        if (bleConn != MeshConnection.CONNECTED || !ble.isReady) return@onMesh
        val now = System.currentTimeMillis()
        val prev = lastSentFix
        val due = when {
            prev == null || mode != lastSentMode -> true
            mode == DrivingMode.MOVING -> now - lastSentAt >= MOVING_INTERVAL_MS ||
                movedFt(prev, fix) > MOVING_DISTANCE_FT
            else -> now - lastSentAt >= PARKED_INTERVAL_MS
        }
        if (due) sendPosition(fix, mode)
    }

    private fun sendPosition(fix: Fix, mode: DrivingMode?) {
        try {
            ble.send(protocol.positionPacket(fix))
            lastSentAt = System.currentTimeMillis()
            lastSentFix = fix
            lastSentMode = mode
        } catch (e: Exception) {
            log("Couldn't encode position: ${e.message}")
        }
    }

    private fun movedFt(a: Fix, b: Fix) =
        TripLogImpl.distanceFt(a.position.lat, a.position.lon, b.position.lat, b.position.lon)

    // --- BLE link (mesh thread) ------------------------------------------------------

    private fun linkState(state: BleTransport.LinkState, deviceName: String?, detail: String?) {
        if (state != BleTransport.LinkState.READY) stopHeartbeat()
        bleConn = when (state) {
            BleTransport.LinkState.IDLE -> MeshConnection.DISCONNECTED
            BleTransport.LinkState.SCANNING -> MeshConnection.SCANNING
            BleTransport.LinkState.BONDING, BleTransport.LinkState.CONNECTING -> MeshConnection.CONNECTING
            // Connected, but not CONNECTED until the config download finishes.
            BleTransport.LinkState.READY -> MeshConnection.CONNECTING
            BleTransport.LinkState.ERROR -> MeshConnection.ERROR
        }
        if (deviceName != null) _state.update { it.copy(deviceName = deviceName) }
        publish(detail)
    }

    private fun linkReady(deviceName: String?) {
        protocol = MeshProtocol()
        myNode = null
        if (deviceName != null) _state.update { it.copy(deviceName = deviceName) }
        bleConn = MeshConnection.CONNECTING
        publish("Reading radio settings…")
        try {
            ble.send(protocol.wantConfig())
        } catch (e: Exception) {
            log("wantConfig failed: ${e.message}")
        }
    }

    private fun handleEvent(e: MeshEvent) {
        when (e) {
            is MeshEvent.MyInfo -> {
                myNode = e.myNodeNum
                if (vehicles.remove(e.myNodeNum) != null) publishFleet()
            }
            is MeshEvent.Owner -> {
                log("Radio is named “${e.longName}”")
                _state.update { it.copy(deviceName = e.longName) }
            }
            is MeshEvent.VehicleUpdate -> upsertVehicle(e.vehicle)
            is MeshEvent.Text -> if (e.message.fromNode != myNode) appendMessage(e.message.copy(mine = false))
            is MeshEvent.ConfigComplete -> onConfigComplete(e.needsProvisioning)
            is MeshEvent.Log -> log(e.line)
        }
    }

    private fun onConfigComplete(needsProvisioning: Boolean) {
        if (!needsProvisioning) {
            bleConn = MeshConnection.CONNECTED
            publish("Connected to ${_state.value.deviceName ?: "radio"}")
            startHeartbeat()
            lastFix?.let { sendPosition(it, null) }
            return
        }
        val name = carName() ?: return
        val now = System.currentTimeMillis()
        if (now - lastProvisionAt < 120_000) {
            bleConn = MeshConnection.ERROR
            publish("Radio didn't keep the ZepDriver settings — try USB setup")
            ble.disconnect()
            return
        }
        lastProvisionAt = now
        bleConn = MeshConnection.PROVISIONING
        publish("Setting up radio as $name… it will restart")
        val packets = try {
            protocol.provisioningPackets(name)
        } catch (e: Exception) {
            log("Couldn't build settings: ${e.message}")
            return
        }
        ble.sendThenReconnect(packets, REBOOT_WAIT_MS)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch(meshThread) {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (ble.isReady) runCatching { ble.send(protocol.heartbeat()) }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // --- fleet & messages (mesh thread) ---------------------------------------------

    private fun upsertVehicle(v: FleetVehicle) {
        if (v.nodeNum == myNode) return
        val old = vehicles[v.nodeNum]
        vehicles[v.nodeNum] = if (old == null) v else v.copy(
            name = v.name.ifBlank { old.name },
            shortName = v.shortName.ifBlank { old.shortName },
            position = v.position ?: old.position,
            positionTimeMillis = v.positionTimeMillis ?: old.positionTimeMillis,
            speedMph = v.speedMph ?: old.speedMph,
            headingDeg = v.headingDeg ?: old.headingDeg,
            lastHeardMillis = maxOf(v.lastHeardMillis, old.lastHeardMillis),
        )
        publishFleet()
    }

    private fun pruneFleet() {
        val cutoff = System.currentTimeMillis() - FLEET_MAX_AGE_MS
        if (vehicles.values.removeAll { it.lastHeardMillis < cutoff } || _fleet.value.size != vehicles.size) {
            publishFleet()
        }
    }

    private fun publishFleet() {
        _fleet.value = vehicles.values.sortedByDescending { it.lastHeardMillis }
        persistSoon()
    }

    private fun appendMessage(m: MeshText) {
        val list = _messages.value
        if (list.any { it.fromNode == m.fromNode && it.timeMillis == m.timeMillis && it.text == m.text }) return
        _messages.value = (list + m).takeLast(MAX_MESSAGES)
        persistSoon()
    }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = scope.launch(meshThread) {
            delay(3_000)
            val fleet = _fleet.value
            val messages = _messages.value
            withContext(Dispatchers.IO) { store.save(fleet, messages) }
        }
    }

    // --- USB flashing / provisioning ---------------------------------------------------

    private fun startUsbJob(flash: Boolean) = onMesh {
        val name = carName() ?: return@onMesh
        if (usbJob?.isActive == true) {
            publish("Already working on the USB radio")
            return@onMesh
        }
        usbConn = if (flash) MeshConnection.FLASHING else MeshConnection.PROVISIONING
        _state.update { it.copy(flashProgress = null) }
        publish(if (flash) "Preparing firmware…" else "Opening USB radio…")
        usbJob = scope.launch(Dispatchers.IO) { runUsb(flash, name) }
    }

    private suspend fun runUsb(flash: Boolean, name: String) {
        try {
            if (flash) {
                val image = loadFirmware()
                val driver = usbDriver()
                _state.update { it.copy(deviceName = driver.device.productName ?: "USB radio") }
                setUsb(MeshConnection.FLASHING, "Flashing Meshtastic ${FirmwareCatalog.version}…")
                _state.update { it.copy(flashProgress = 0f) }
                val port = usb.open(driver, 115_200)
                try {
                    EspFlasher(port) { log(it) }.flash(listOf(FlashImage(0, image))) { p ->
                        _state.update { it.copy(flashProgress = p.coerceIn(0f, 1f)) }
                    }
                } finally {
                    port.close()
                }
                _state.update { it.copy(flashProgress = null) }
                log("Flash complete; waiting for the radio to start…")
                delay(BOOT_WAIT_MS)
            }
            setUsb(MeshConnection.PROVISIONING, "Setting up radio as $name…")
            provisionOverSerial(usbDriver(), name)
            log("Radio set up as $name")
            withContext(meshThread) {
                usbConn = null
                publish("Radio ready — connect over Bluetooth")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "USB job failed", e)
            log("Failed: ${e.message ?: e.javaClass.simpleName}")
            _state.update { it.copy(flashProgress = null) }
            withContext(meshThread) {
                usbConn = MeshConnection.ERROR
                publish(e.message ?: "USB radio setup failed")
            }
        }
    }

    private suspend fun setUsb(conn: MeshConnection, status: String) = withContext(meshThread) {
        usbConn = conn
        publish(status)
    }

    /** The plugged-in radio with permission granted; waits briefly for it to (re)appear after a reset. */
    private suspend fun usbDriver(): UsbSerialDriver {
        var driver: UsbSerialDriver? = null
        for (i in 0 until 20) {
            driver = usb.findDriver()
            if (driver != null) break
            delay(500)
        }
        usb.refresh()
        val d = driver ?: throw IOException("Plug the radio into the phone with a USB-OTG cable")
        if (!usb.ensurePermission(d.device)) throw IOException("USB permission was denied")
        return d
    }

    private suspend fun provisionOverSerial(driver: UsbSerialDriver, name: String) {
        val port = usb.open(driver, 115_200)
        try {
            // Both low: run mode (no reset / bootloader) on CP210x and ESP32-S3 native USB.
            port.setDtr(false)
            port.setRts(false)
            val proto = MeshProtocol()
            val deframer = StreamFraming.Deframer()
            // Wake the serial API (it starts in debug-text mode), then ask for the config.
            port.write(ByteArray(32) { 0xC3.toByte() })
            delay(100)
            port.write(StreamFraming.frame(proto.wantConfig()))

            val buf = ByteArray(4096)
            var gotInfo = false
            var configured = false
            val deadline = System.currentTimeMillis() + SERIAL_CONFIG_TIMEOUT_MS
            while (!configured && System.currentTimeMillis() < deadline) {
                val n = port.read(buf, 500)
                if (n <= 0) continue
                for (payload in deframer.feed(buf, n)) {
                    for (e in proto.handleFromRadio(payload)) when (e) {
                        is MeshEvent.MyInfo -> gotInfo = true
                        is MeshEvent.ConfigComplete -> configured = true
                        is MeshEvent.Owner -> log("Radio was named “${e.longName}”")
                        is MeshEvent.Log -> log(e.line)
                        else -> Unit
                    }
                }
            }
            if (!gotInfo && !configured) throw IOException("The radio didn't answer — is Meshtastic installed?")
            log("Writing ZepDriver settings…")
            for (packet in proto.provisioningPackets(name)) {
                port.write(StreamFraming.frame(packet))
                delay(300)
            }
            // Let the radio commit before the port closes.
            val until = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < until) port.read(buf, 200)
        } finally {
            port.close()
        }
    }

    /** The factory image: bundled in the APK, else a cached download, else downloaded now. */
    private suspend fun loadFirmware(): ByteArray = withContext(Dispatchers.IO) {
        val imageName = FirmwareCatalog.factoryImageName
        val version = FirmwareCatalog.version
        val bundled = try {
            app.assets.open("firmware/$imageName").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
        if (bundled != null) {
            log("Using bundled firmware $version")
            return@withContext bundled
        }
        val dir = File(app.filesDir, "firmware/$version")
        val cached = File(dir, imageName)
        if (cached.isFile && cached.length() > 0) {
            log("Using downloaded firmware $version")
            return@withContext cached.readBytes()
        }
        if (!Net.isOnline(app)) {
            throw IOException("Firmware isn't downloaded yet — connect to the internet once and try again")
        }
        log("Downloading firmware…")
        publishStatus("Downloading firmware…")
        val zip = File(app.cacheDir, "firmware-download.zip")
        try {
            Net.download(FirmwareCatalog.downloadUrl, zip) { p ->
                publishStatus("Downloading firmware… ${(p * 100).toInt()}%")
            }
            val tmp = File(dir, "$imageName.tmp")
            dir.mkdirs()
            var found = false
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val n = entry.name
                    if (!entry.isDirectory && (n == imageName || n.endsWith("/$imageName"))) {
                        tmp.outputStream().use { zin.copyTo(it) }
                        found = true
                        break
                    }
                }
            }
            if (!found) throw IOException("$imageName not found in the firmware archive")
            if (!tmp.renameTo(cached)) throw IOException("Couldn't save firmware")
            // Drop other cached versions.
            File(app.filesDir, "firmware").listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
            log("Firmware $version downloaded")
            cached.readBytes()
        } finally {
            zip.delete()
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private fun onMesh(block: () -> Unit) {
        scope.launch(meshThread) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "mesh error", e)
                log("Error: ${e.message}")
            } catch (e: NotImplementedError) {
                Log.e(TAG, "mesh: core not implemented", e)
            }
        }
    }

    private fun carName(): String? {
        val name = settings.profile.value.carName.trim()
        if (name.isEmpty()) {
            nameBlank = true
            publish("Set a car name first")
            return null
        }
        return name
    }

    private fun carNameOk() = carName() != null

    /** Recomputes the visible connection; [status] replaces the status line when non-null. */
    private fun publish(status: String? = null) {
        val conn = when {
            nameBlank -> MeshConnection.NO_CAR_NAME
            else -> usbConn ?: bleConn
        }
        _state.update { it.copy(connection = conn, status = status ?: it.status) }
    }

    private fun publishStatus(status: String) = _state.update { it.copy(status = status) }

    private fun log(line: String) {
        val stamped = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $line"
        _state.update { it.copy(log = (it.log + stamped).takeLast(MAX_LOG)) }
    }

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        var end = length
        while (end > 0 && substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return substring(0, end)
    }

    private companion object {
        const val TAG = "Mesh"
        const val MOVING_INTERVAL_MS = 60_000L
        const val MOVING_DISTANCE_FT = 500.0
        const val PARKED_INTERVAL_MS = 10 * 60_000L
        const val HEARTBEAT_MS = 5 * 60_000L
        const val REBOOT_WAIT_MS = 10_000L
        const val BOOT_WAIT_MS = 8_000L
        const val SERIAL_CONFIG_TIMEOUT_MS = 30_000L
        const val FLEET_MAX_AGE_MS = 24 * 60 * 60_000L
        const val MAX_MESSAGES = 200
        const val MAX_LOG = 200
        const val MAX_TEXT_BYTES = 200
    }
}
