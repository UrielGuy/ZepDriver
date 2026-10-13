package com.zepdriver.core.mesh

import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.LocalProjection
import com.zepdriver.core.geo.MPH_PER_MPS
import com.zepdriver.core.geo.normalizeDeg
import java.security.SecureRandom
import kotlin.math.roundToLong

/**
 * Compiled-in network settings shared by every ZepDriver install.
 *
 * Every phone running ZepDriver provisions its radio with exactly these
 * values, so all ZepDriver radios form one mesh with no QR codes or setup.
 */
object ZepMesh {
    /** Primary channel name; also determines the LoRa frequency slot. */
    const val CHANNEL_NAME = "ZepDriver"
    /** BLE pairing PIN set during provisioning. */
    const val BLE_PIN = 123456

    /**
     * AES-256 channel key of the ZepDriver channel.
     *
     * **Not a secret**: it ships inside every APK, so anyone can extract it.
     * It only keeps ZepDriver traffic separate from other meshes; treat
     * everything sent on the mesh as public. Generated randomly once
     * (2026-10-13) and fixed forever: changing it splits the fleet.
     */
    val PSK: ByteArray get() = PSK_BYTES.copyOf()

    private val PSK_BYTES = byteArrayOf(
        0x0f, 0xa1.toByte(), 0x68, 0xca.toByte(), 0x72, 0x87.toByte(), 0xa4.toByte(), 0x86.toByte(),
        0xc5.toByte(), 0x2e, 0x25, 0x44, 0x56, 0x8a.toByte(), 0x1e, 0x3d,
        0x65, 0x2a, 0xea.toByte(), 0x87.toByte(), 0xf5.toByte(), 0xe5.toByte(), 0xae.toByte(), 0xfc.toByte(),
        0xb5.toByte(), 0x6e, 0x90.toByte(), 0xe5.toByte(), 0xa7.toByte(), 0xa2.toByte(), 0xf8.toByte(), 0xb8.toByte(),
    )

    /** LoRa region: US 902–928 MHz (the recommended Heltec V3 is the 915 MHz version). */
    const val REGION = LoRaConfig.REGION_US

    /**
     * MEDIUM_FAST: the playa is busy and distances are a few miles, so trade
     * range for much less airtime than the default LONG_FAST. Together with
     * the channel name (which picks the frequency slot) this keeps us off
     * Meshtastic's default public channel.
     */
    const val MODEM_PRESET = LoRaConfig.PRESET_MEDIUM_FAST

    /** Max rebroadcasts of a packet. */
    const val HOP_LIMIT = 3

    /** Channel position precision: 32 = full precision. 0 would make the radio strip phone positions. */
    const val POSITION_PRECISION = 32

    /** How often to send our position while moving. */
    const val POSITION_INTERVAL_MOVING_MS = 60_000L
    /** How often to send our position while parked. */
    const val POSITION_INTERVAL_PARKED_MS = 10 * 60_000L
    /** Also send as soon as we moved this far since the last send. */
    const val POSITION_RESEND_DISTANCE_FT = 500.0

    /**
     * The radio's own position broadcast interval. The phone sends positions
     * itself (adaptive interval above), so the radio's timer is set long to
     * avoid duplicate airtime.
     */
    const val RADIO_POSITION_BROADCAST_SECS = 24 * 60 * 60L

    /** Longest owner long name the firmware stores/transmits (NodeInfoLite.long_name, 25 incl. NUL). */
    const val MAX_LONG_NAME_BYTES = 24
    /** User.short_name max_size 5 incl. NUL. */
    const val MAX_SHORT_NAME_BYTES = 4

    /** The owner long name used for [carName]: trimmed, whitespace collapsed, ≤ 24 UTF-8 bytes. */
    fun longNameFor(carName: String): String =
        truncateUtf8(carName.trim().replace(Regex("\\s+"), " "), MAX_LONG_NAME_BYTES).trim()

    /**
     * A ≤ 4-byte short name: initials of a multi-word name ("Disco Zebra" →
     * "DZ"), else the first characters of the name ("Zephyr" → "Zeph").
     */
    fun shortNameFor(carName: String): String {
        val name = longNameFor(carName)
        val words = name.split(' ').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val candidate = when {
            words.size >= 2 -> words.joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)) }.uppercase()
            words.size == 1 -> words[0]
            else -> name.replace(" ", "")
        }
        val short = truncateUtf8(candidate, MAX_SHORT_NAME_BYTES)
        return short.ifEmpty { "ZEP" }
    }

    /**
     * Whether to broadcast a new position now. [lastSent] is the fix we last
     * sent (null if none yet), [moving] the app's driving mode.
     */
    fun shouldSendPosition(lastSent: Fix?, now: Fix, moving: Boolean): Boolean {
        if (lastSent == null) return true
        val elapsed = now.timeMillis - lastSent.timeMillis
        val interval = if (moving) POSITION_INTERVAL_MOVING_MS else POSITION_INTERVAL_PARKED_MS
        if (elapsed >= interval) return true
        val movedFt = LocalProjection(lastSent.position).toXy(now.position).length()
        return movedFt > POSITION_RESEND_DISTANCE_FT
    }

    /** Longest prefix of [s] whose UTF-8 encoding fits in [maxBytes], never splitting a code point. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}

/**
 * Meshtastic BLE GATT identifiers (from the firmware's BluetoothCommon.h).
 *
 * BLE flow: connect → request MTU 512 → bond (PIN [ZepMesh.BLE_PIN] once
 * provisioned) → enable notifications on [FROMNUM] → write
 * [MeshProtocol.wantConfig] to [TORADIO] → read [FROMRADIO] repeatedly,
 * passing each value to [MeshProtocol.handleFromRadio], until a read returns
 * 0 bytes. Each [FROMNUM] notification means "more to read": drain
 * [FROMRADIO] again the same way. Every ToRadio is one write (no framing).
 */
object MeshBle {
    const val SERVICE = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
    const val TORADIO = "f75c76d2-129e-4dad-a1dd-7866124401e7"
    const val FROMRADIO = "2c55e69e-4993-11ed-b878-0242ac120002"
    const val FROMNUM = "ed9da18c-a800-4f66-a670-aa7547e34453"
    const val LOGRADIO = "5a3d6e49-06e6-4423-9944-e9de8cdf9547"
    const val MTU = 512
}

/** Another vehicle as last heard on the mesh. */
data class FleetVehicle(
    val nodeNum: Long,
    val name: String,
    val shortName: String,
    val position: LatLon?,
    /** When the position was taken (epoch millis), if known. */
    val positionTimeMillis: Long?,
    val speedMph: Double?,
    val headingDeg: Double?,
    /** When we last heard anything from this node (epoch millis). */
    val lastHeardMillis: Long,
)

data class MeshText(
    val fromNode: Long,
    val fromName: String,
    val text: String,
    val timeMillis: Long,
    val mine: Boolean,
)

sealed interface MeshEvent {
    data class MyInfo(val myNodeNum: Long) : MeshEvent
    data class Owner(val longName: String, val shortName: String) : MeshEvent
    data class VehicleUpdate(val vehicle: FleetVehicle) : MeshEvent
    data class Text(val message: MeshText) : MeshEvent
    /**
     * Initial config download finished; [needsProvisioning] if channel/owner
     * differ from ZepDriver's. [reasons] says what differs (for the log).
     */
    data class ConfigComplete(val needsProvisioning: Boolean, val reasons: List<String> = emptyList()) : MeshEvent
    data class Log(val line: String) : MeshEvent
}

/**
 * Meshtastic client-API protocol (ToRadio / FromRadio protobufs), transport
 * agnostic. BLE carries the protobuf bytes as-is (see [MeshBle]); serial wraps
 * them with [StreamFraming].
 *
 * Session flow (both transports):
 * 1. Set [carName] (if known), send [wantConfig].
 * 2. Feed every FromRadio to [handleFromRadio]. The radio dumps MyInfo, its
 *    node DB, config and channels, then [MeshEvent.ConfigComplete].
 * 3. If `needsProvisioning`, send every [provisioningPackets] message in
 *    order. On commit the radio saves and reboots (BLE drops; serial shows
 *    [REBOOTED_LOG]): reconnect and start again at 1.
 * 4. Otherwise send [positionPacket] per [ZepMesh.shouldSendPosition],
 *    [textPacket] for messages, and [heartbeat] every few minutes (serial
 *    needs it to keep the API session alive; harmless over BLE).
 *
 * Thread-safe (all public methods synchronize on this object).
 */
class MeshProtocol(private val nowMillis: () -> Long = System::currentTimeMillis) {
    var myNodeNum: Long? = null
        private set

    /**
     * The car name the user chose. When set, [MeshEvent.ConfigComplete] also
     * requests provisioning if the radio's owner name doesn't match it.
     */
    @Volatile
    var carName: String? = null

    /** Firmware version reported by the radio (DeviceMetadata), once known. */
    var firmwareVersion: String? = null
        private set

    /** Our radio's owner as last reported. */
    var owner: User? = null
        private set

    private val random = SecureRandom()
    private var configNonce: Long = 0
    private var heartbeatNonce: Long = 0
    private val channels = sortedMapOf<Int, Channel>()
    private var lora: LoRaConfig? = null
    private var bluetooth: BluetoothConfig? = null
    private var positionConfig: PositionConfig? = null
    private val nodes = HashMap<Long, NodeEntry>()

    private class NodeEntry(val num: Long) {
        var longName: String? = null
        var shortName: String? = null
        var position: LatLon? = null
        var positionTimeMillis: Long? = null
        var speedMph: Double? = null
        var headingDeg: Double? = null
        var lastHeardMillis: Long = 0

        fun toVehicle() = FleetVehicle(
            nodeNum = num,
            name = longName?.takeIf { it.isNotBlank() } ?: defaultName(num),
            shortName = shortName?.takeIf { it.isNotBlank() } ?: defaultShortName(num),
            position = position,
            positionTimeMillis = positionTimeMillis,
            speedMph = speedMph,
            headingDeg = headingDeg,
            lastHeardMillis = lastHeardMillis,
        )
    }

    /** Every vehicle currently known (excluding our own radio). */
    @Synchronized
    fun vehicles(): List<FleetVehicle> =
        nodes.values.filter { it.num != myNodeNum }.map { it.toVehicle() }.sortedByDescending { it.lastHeardMillis }

    /** ToRadio asking the node to dump its config and node DB. */
    @Synchronized
    fun wantConfig(): ByteArray {
        var nonce: Long
        do {
            nonce = random.nextInt().toLong() and 0xFFFFFFFFL
            // 69420 / 69421 are special "config only" / "nodes only" nonces in recent firmware.
        } while (nonce == 0L || nonce == 69420L || nonce == 69421L)
        configNonce = nonce
        channels.clear()
        lora = null
        bluetooth = null
        positionConfig = null
        return ToRadio.WantConfig(nonce).encode()
    }

    /** Decodes one FromRadio message. Never throws on bad input (returns a [MeshEvent.Log]). */
    @Synchronized
    fun handleFromRadio(bytes: ByteArray): List<MeshEvent> {
        val msg = try {
            FromRadio.decode(bytes)
        } catch (e: ProtoException) {
            return listOf(MeshEvent.Log("Undecodable FromRadio (${bytes.size} bytes): ${e.message}"))
        }
        return try {
            handle(msg)
        } catch (e: ProtoException) {
            listOf(MeshEvent.Log("Undecodable payload in FromRadio: ${e.message}"))
        }
    }

    private fun handle(msg: FromRadio): List<MeshEvent> = when (msg) {
        is FromRadio.MyInfo -> {
            myNodeNum = msg.info.myNodeNum
            listOf(MeshEvent.MyInfo(msg.info.myNodeNum))
        }
        is FromRadio.Node -> handleNodeInfo(msg.node)
        is FromRadio.ConfigMsg -> {
            msg.config.lora?.let { lora = it }
            msg.config.bluetooth?.let { bluetooth = it }
            msg.config.position?.let { positionConfig = it }
            emptyList()
        }
        is FromRadio.ChannelMsg -> {
            channels[msg.channel.index] = msg.channel
            emptyList()
        }
        is FromRadio.ConfigCompleteId -> {
            val reasons = provisioningReasons()
            listOf(MeshEvent.ConfigComplete(reasons.isNotEmpty(), reasons))
        }
        is FromRadio.Metadata -> {
            firmwareVersion = msg.metadata.firmwareVersion.ifBlank { null }
            listOf(MeshEvent.Log("Radio firmware ${msg.metadata.firmwareVersion}"))
        }
        is FromRadio.Rebooted -> listOf(MeshEvent.Log(REBOOTED_LOG))
        is FromRadio.LogRecord -> listOf(MeshEvent.Log("radio: ${msg.message}"))
        is FromRadio.ClientNotification -> listOf(MeshEvent.Log("radio: ${msg.message}"))
        is FromRadio.Packet -> handlePacket(msg.packet)
        is FromRadio.Other -> emptyList()
    }

    private fun handleNodeInfo(info: NodeInfo): List<MeshEvent> {
        if (info.num == myNodeNum) {
            val user = info.user ?: return emptyList()
            owner = user
            return listOf(MeshEvent.Owner(user.longName, user.shortName))
        }
        if (info.channel != 0 || info.viaMqtt) return emptyList()
        val e = nodes.getOrPut(info.num) { NodeEntry(info.num) }
        info.user?.let { e.longName = it.longName; e.shortName = it.shortName }
        info.position?.let { applyPosition(e, it, fallbackMillis = info.lastHeard.takeIf { s -> s > 0 }?.times(1000)) }
        if (info.lastHeard > 0) e.lastHeardMillis = maxOf(e.lastHeardMillis, info.lastHeard * 1000)
        return listOf(MeshEvent.VehicleUpdate(e.toVehicle()))
    }

    private fun handlePacket(p: MeshPacket): List<MeshEvent> {
        val data = p.decoded ?: return emptyList() // still encrypted: not for our channel
        if (p.from == myNodeNum || p.from == 0L) return emptyList()
        if (p.channel != 0 || p.viaMqtt) return emptyList()
        val now = nowMillis()
        return when (data.portnum) {
            PortNum.POSITION_APP -> {
                val e = touch(p.from, now)
                applyPosition(e, Position.decode(data.payload), fallbackMillis = now)
                listOf(MeshEvent.VehicleUpdate(e.toVehicle()))
            }
            PortNum.NODEINFO_APP -> {
                val user = User.decode(data.payload)
                val e = touch(p.from, now)
                e.longName = user.longName
                e.shortName = user.shortName
                listOf(MeshEvent.VehicleUpdate(e.toVehicle()))
            }
            PortNum.TEXT_MESSAGE_APP -> {
                if (p.to != NodeNum.BROADCAST && p.to != myNodeNum) return emptyList()
                val e = touch(p.from, now)
                val text = String(data.payload, Charsets.UTF_8)
                listOf(
                    MeshEvent.VehicleUpdate(e.toVehicle()),
                    MeshEvent.Text(MeshText(p.from, e.toVehicle().name, text, now, mine = false)),
                )
            }
            else -> {
                // Any other traffic still tells us the node is alive.
                if (nodes.containsKey(p.from)) listOf(MeshEvent.VehicleUpdate(touch(p.from, now).toVehicle())) else emptyList()
            }
        }
    }

    private fun touch(num: Long, now: Long): NodeEntry =
        nodes.getOrPut(num) { NodeEntry(num) }.also { it.lastHeardMillis = maxOf(it.lastHeardMillis, now) }

    private fun applyPosition(e: NodeEntry, pos: Position, fallbackMillis: Long?) {
        val lat = pos.latitudeI ?: return
        val lon = pos.longitudeI ?: return
        if (lat == 0 && lon == 0) return // "no position"
        e.position = LatLon(lat / 1e7, lon / 1e7)
        val t = pos.time.takeIf { it > MIN_PLAUSIBLE_EPOCH_S }?.times(1000)
        e.positionTimeMillis = t ?: fallbackMillis
        e.speedMph = pos.groundSpeed?.let { it / KMH_PER_MPH }
        e.headingDeg = pos.groundTrack?.let { normalizeDeg(it / Position.TRACK_UNITS_PER_DEGREE) }
    }

    /**
     * Why the radio needs (re)provisioning, from the config seen during the
     * last dump; empty when it is set up for ZepDriver. Uses [carName] when set.
     */
    @Synchronized
    fun provisioningReasons(): List<String> {
        val r = mutableListOf<String>()
        val primary = channels[0]
        when {
            primary == null -> r += "primary channel unknown"
            else -> {
                val s = primary.settings
                if (primary.role != Channel.ROLE_PRIMARY) r += "channel 0 is not primary"
                if (s?.name != ZepMesh.CHANNEL_NAME) r += "channel name '${s?.name ?: ""}'"
                if (s == null || !s.psk.contentEquals(ZepMesh.PSK)) r += "channel key differs"
                if ((s?.moduleSettings?.positionPrecision ?: 0) != ZepMesh.POSITION_PRECISION) r += "position precision"
            }
        }
        channels.values.filter { it.index != 0 && it.role != Channel.ROLE_DISABLED }.forEach {
            r += "extra channel ${it.index} '${it.settings?.name ?: ""}'"
        }
        val ownerName = owner?.longName.orEmpty()
        if (ownerName.isBlank()) r += "no car name on radio"
        carName?.takeIf { it.isNotBlank() }?.let { wanted ->
            if (ownerName.isNotBlank() && ownerName != ZepMesh.longNameFor(wanted)) r += "car name '$ownerName'"
        }
        val l = lora
        if (l == null) r += "LoRa config unknown"
        else {
            if (l.region != ZepMesh.REGION) r += "region ${l.region}"
            if (!l.usePreset || l.modemPreset != ZepMesh.MODEM_PRESET) r += "modem preset"
            if (!l.txEnabled) r += "transmit disabled"
        }
        bluetooth?.let { if (!it.enabled || it.mode != BluetoothConfig.MODE_FIXED_PIN || it.fixedPin != ZepMesh.BLE_PIN.toLong()) r += "BLE pairing" }
        return r
    }

    /** ToRadio broadcasting our position on the ZepDriver channel. */
    @Synchronized
    fun positionPacket(fix: Fix): ByteArray {
        val pos = Position(
            latitudeI = (fix.position.lat * 1e7).roundToLong().toInt(),
            longitudeI = (fix.position.lon * 1e7).roundToLong().toInt(),
            time = fix.timeMillis / 1000,
            locationSource = 3, // LOC_EXTERNAL: from the phone
            groundSpeed = fix.speedMps?.let { (it * 3.6).roundToLong().coerceAtLeast(0) },
            groundTrack = fix.bearingDeg?.let { (normalizeDeg(it) * Position.TRACK_UNITS_PER_DEGREE).roundToLong() % 36_000_000L },
            precisionBits = ZepMesh.POSITION_PRECISION,
        )
        return broadcast(PortNum.POSITION_APP, pos.encode(), wantAck = false)
    }

    /** ToRadio broadcasting a text message on the ZepDriver channel (capped to [MAX_TEXT_BYTES]). */
    @Synchronized
    fun textPacket(text: String): ByteArray =
        broadcast(PortNum.TEXT_MESSAGE_APP, ZepMesh.truncateUtf8(text, MAX_TEXT_BYTES).toByteArray(Charsets.UTF_8), wantAck = true)

    /** The [MeshText] to show for a message we just sent with [textPacket]. */
    @Synchronized
    fun sentText(text: String): MeshText = MeshText(
        fromNode = myNodeNum ?: 0,
        fromName = owner?.longName?.takeIf { it.isNotBlank() } ?: carName ?: "Me",
        text = ZepMesh.truncateUtf8(text, MAX_TEXT_BYTES),
        timeMillis = nowMillis(),
        mine = true,
    )

    private fun broadcast(port: Int, payload: ByteArray, wantAck: Boolean): ByteArray =
        ToRadio.Packet(
            MeshPacket(
                to = NodeNum.BROADCAST,
                channel = 0,
                decoded = MeshData(port, payload),
                id = newPacketId(),
                hopLimit = ZepMesh.HOP_LIMIT,
                wantAck = wantAck,
            ),
        ).encode()

    /**
     * ToRadio admin messages (to our own node) that set the owner to
     * [carName], US region, the ZepDriver channel, fixed BLE PIN and position
     * settings, then commit. Send in order after [MeshEvent.MyInfo].
     *
     * Settings read during the config dump are used as the base, so radio
     * settings ZepDriver doesn't manage are kept. Secondary channels seen in
     * the dump are disabled (only ZepDriver traffic is wanted). The radio
     * reboots after the commit.
     *
     * @throws IllegalStateException if our node number is not known yet.
     */
    @Synchronized
    fun provisioningPackets(carName: String): List<ByteArray> {
        val me = myNodeNum ?: throw IllegalStateException("Radio node number unknown: send wantConfig and wait for MyInfo")
        val longName = ZepMesh.longNameFor(carName)
        require(longName.isNotEmpty()) { "Car name is empty" }
        this.carName = carName

        val baseLora = lora ?: LoRaConfig()
        val newLora = LoRaConfig(
            usePreset = true,
            modemPreset = ZepMesh.MODEM_PRESET,
            bandwidth = baseLora.bandwidth,
            spreadFactor = baseLora.spreadFactor,
            codingRate = baseLora.codingRate,
            frequencyOffset = baseLora.frequencyOffset,
            region = ZepMesh.REGION,
            hopLimit = ZepMesh.HOP_LIMIT,
            txEnabled = true,
            txPower = baseLora.txPower,          // 0 = max legal for the region
            channelNum = 0,                      // slot derived from the channel name
            overrideDutyCycle = baseLora.overrideDutyCycle,
            sx126xRxBoostedGain = baseLora.sx126xRxBoostedGain,
            overrideFrequency = 0f,
            unknown = baseLora.unknown,
        )
        val newBt = BluetoothConfig(
            enabled = true,
            mode = BluetoothConfig.MODE_FIXED_PIN,
            fixedPin = ZepMesh.BLE_PIN.toLong(),
            unknown = bluetooth?.unknown ?: ByteArray(0),
        )
        val basePos = positionConfig ?: PositionConfig()
        val newPos = PositionConfig(
            positionBroadcastSecs = ZepMesh.RADIO_POSITION_BROADCAST_SECS,
            positionBroadcastSmartEnabled = false,
            fixedPosition = false,               // true would make the radio ignore phone positions
            gpsUpdateInterval = basePos.gpsUpdateInterval,
            positionFlags = basePos.positionFlags,
            broadcastSmartMinimumDistance = basePos.broadcastSmartMinimumDistance,
            broadcastSmartMinimumIntervalSecs = basePos.broadcastSmartMinimumIntervalSecs,
            gpsMode = PositionConfig.GPS_NOT_PRESENT, // Heltec V3 has no GPS; the phone is the GPS
            unknown = basePos.unknown,
        )
        val primary = Channel(
            index = 0,
            settings = ChannelSettings(
                psk = ZepMesh.PSK,
                name = ZepMesh.CHANNEL_NAME,
                moduleSettings = ModuleSettings(positionPrecision = ZepMesh.POSITION_PRECISION),
            ),
            role = Channel.ROLE_PRIMARY,
        )
        val admin = buildList {
            add(AdminMessage.BeginEditSettings())
            add(AdminMessage.SetOwner(User(longName = longName, shortName = ZepMesh.shortNameFor(carName))))
            add(AdminMessage.SetConfig(Config(newLora)))
            add(AdminMessage.SetChannel(primary))
            channels.values.filter { it.index in 1..7 && it.role != Channel.ROLE_DISABLED }.forEach {
                add(AdminMessage.SetChannel(Channel(it.index, ChannelSettings(), Channel.ROLE_DISABLED)))
            }
            add(AdminMessage.SetConfig(Config(newBt)))
            add(AdminMessage.SetConfig(Config(newPos)))
            add(AdminMessage.CommitEditSettings())
        }
        return admin.map { adminPacket(me, it) }
    }

    private fun adminPacket(me: Long, msg: AdminMessage): ByteArray = ToRadio.Packet(
        MeshPacket(
            // from stays 0: the radio treats from == 0 as its local client (no session passkey needed).
            to = me,
            channel = 0,
            decoded = MeshData(PortNum.ADMIN_APP, msg.encode(), wantResponse = false),
            id = newPacketId(),
            priority = MeshPacket.PRIORITY_RELIABLE,
        ),
    ).encode()

    /** Periodic keepalive. */
    @Synchronized
    fun heartbeat(): ByteArray = ToRadio.Heartbeat(++heartbeatNonce and 0xFFFFFFFFL).encode()

    /** Tells the radio the client is going away (serial; optional over BLE). */
    fun disconnect(): ByteArray = ToRadio.Disconnect.encode()

    private fun newPacketId(): Long {
        var id: Long
        do id = random.nextInt().toLong() and 0xFFFFFFFFL while (id == 0L)
        return id
    }

    companion object {
        /** Cap for outgoing text payloads (Data.payload max is 233 bytes). */
        const val MAX_TEXT_BYTES = 200

        /** [MeshEvent.Log] line emitted when the radio reports it rebooted: reconnect / resend [wantConfig]. */
        const val REBOOTED_LOG = "Radio rebooted"

        private const val KMH_PER_MPH = 1.609344
        private const val MIN_PLAUSIBLE_EPOCH_S = 1_600_000_000L

        internal fun defaultName(num: Long) = "Car %04x".format(num and 0xFFFF)
        internal fun defaultShortName(num: Long) = "%04x".format(num and 0xFFFF)
    }
}

/** Meshtastic serial framing: 0x94 0xC3, big-endian u16 length, payload. */
object StreamFraming {
    const val START1: Int = 0x94
    const val START2: Int = 0xC3
    /** Largest payload the firmware accepts/sends in one frame. */
    const val MAX_PAYLOAD = 512

    /**
     * Send once after opening the port, before the first frame: wakes the
     * radio's serial API (same as the Meshtastic CLI does).
     */
    val WAKE: ByteArray get() = ByteArray(32) { START2.toByte() }

    /** Serial port speed of the Meshtastic serial API. */
    const val BAUD = 115_200

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} > $MAX_PAYLOAD bytes" }
        val out = ByteArray(4 + payload.size)
        out[0] = START1.toByte()
        out[1] = START2.toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        return out
    }

    /**
     * Splits a byte stream into payloads, skipping debug text between frames.
     * Frames may be split across [feed] calls. Bytes outside frames (the
     * firmware's debug console) are passed to [onDebugText] line by line.
     * Zero-length frames are dropped.
     */
    class Deframer(private val onDebugText: (String) -> Unit = {}) {
        private var state = 0
        private val header = ByteArray(4)
        private var length = 0
        private var payload = ByteArray(0)
        private var filled = 0
        private val text = java.io.ByteArrayOutputStream()

        fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for (i in 0 until length) step(bytes[i], out)
            return out
        }

        private fun step(b: Byte, out: MutableList<ByteArray>) {
            val v = b.toInt() and 0xFF
            when (state) {
                0 -> if (v == START1) { header[0] = b; state = 1 } else debugByte(v)
                1 -> if (v == START2) { header[1] = b; state = 2 } else {
                    state = 0
                    debugByte(START1)
                    step(b, out) // might itself be START1
                }
                2 -> { header[2] = b; state = 3 }
                3 -> {
                    header[3] = b
                    val len = ((header[2].toInt() and 0xFF) shl 8) or v
                    if (len > MAX_PAYLOAD) {
                        // Not a real header: resync on the bytes after the first START1.
                        state = 0
                        debugByte(START1)
                        val rest = header.copyOfRange(1, 4)
                        rest.forEach { step(it, out) }
                    } else if (len == 0) {
                        state = 0
                    } else {
                        this.length = len
                        payload = ByteArray(len)
                        filled = 0
                        state = 4
                    }
                }
                4 -> {
                    payload[filled++] = b
                    if (filled == this.length) {
                        out += payload
                        state = 0
                    }
                }
            }
        }

        private fun debugByte(v: Int) {
            if (v == '\n'.code) {
                flushText()
            } else if (v != '\r'.code) {
                text.write(v)
                if (text.size() >= 1024) flushText()
            }
        }

        private fun flushText() {
            if (text.size() > 0) {
                val line = text.toString(Charsets.UTF_8).trim()
                text.reset()
                if (line.isNotEmpty()) onDebugText(line)
            }
        }
    }
}
