package com.zepdriver.core.mesh

/*
 * Hand-written encoders/decoders for the subset of the Meshtastic client API
 * that ZepDriver uses. Field numbers, types and enum values were taken from the
 * Meshtastic protobuf definitions (mesh.proto, portnums.proto, admin.proto,
 * config.proto, channel.proto); no generated code or .proto text is included.
 *
 * Conventions:
 * - uint32 / fixed32 values are carried as non-negative Longs.
 * - `optional` (explicit presence) fields are nullable; proto3 implicit
 *   fields use their default and are not written when default.
 * - Config sub-messages keep fields we do not model in `unknown`, so a config
 *   read from the radio can be modified and written back without losing
 *   settings this app doesn't know about.
 */

/** Meshtastic `PortNum` values (portnums.proto). */
object PortNum {
    const val UNKNOWN_APP = 0
    const val TEXT_MESSAGE_APP = 1
    const val POSITION_APP = 3
    const val NODEINFO_APP = 4
    const val ROUTING_APP = 5
    const val ADMIN_APP = 6
}

/** Special node numbers. */
object NodeNum {
    /** MeshPacket.to for broadcasts. */
    const val BROADCAST = 0xFFFFFFFFL
}

private fun u32(v: Long): Int = v.toInt()

// ---------------------------------------------------------------------------------------------
// mesh.proto
// ---------------------------------------------------------------------------------------------

/** `Position`. Units: lat/lon 1e-7 degrees, altitude meters MSL, time epoch seconds. */
class Position(
    val latitudeI: Int? = null,                 // 1 optional sfixed32
    val longitudeI: Int? = null,                // 2 optional sfixed32
    val altitude: Int? = null,                  // 3 optional int32
    val time: Long = 0,                         // 4 fixed32
    val locationSource: Int = 0,                // 5 enum LocSource
    val timestamp: Long = 0,                    // 7 fixed32
    val altitudeHae: Int? = null,               // 9 optional sint32
    /**
     * 15 optional uint32, km/h. (The proto comment says km/h; firmware's GPS
     * code fills it with `speed.kmph()`.)
     */
    val groundSpeed: Long? = null,
    /**
     * 16 optional uint32. The proto comment says "1/100 degrees", but the
     * stock firmware's GPS code stores the course scaled to 1e-5 degrees
     * ("Scale the heading (in degrees * 10^-2) to match the expected degrees *
     * 10^-5", src/gps/GPS.cpp). ZepDriver follows the firmware: 1e-5 degrees.
     */
    val groundTrack: Long? = null,
    val satsInView: Int = 0,                    // 19 uint32
    val seqNumber: Long = 0,                    // 22 uint32
    val precisionBits: Int = 0,                 // 23 uint32
) {
    val latitude: Double? get() = latitudeI?.let { it / 1e7 }
    val longitude: Double? get() = longitudeI?.let { it / 1e7 }

    fun encode(): ByteArray = ProtoWriter().apply {
        latitudeI?.let { fixed32Always(1, it) }
        longitudeI?.let { fixed32Always(2, it) }
        altitude?.let { int32Always(3, it) }
        fixed32(4, u32(time))
        uint(5, locationSource.toLong())
        fixed32(7, u32(timestamp))
        altitudeHae?.let { varintAlways(9, ProtoWriter.zigzag32(it).toLong() and 0xFFFFFFFFL) }
        groundSpeed?.let { varintAlways(15, it) }
        groundTrack?.let { varintAlways(16, it) }
        uint(19, satsInView.toLong())
        uint(22, seqNumber)
        uint(23, precisionBits.toLong())
    }.toByteArray()

    companion object {
        /** 1e-5 degrees per unit of [groundTrack] (firmware convention). */
        const val TRACK_UNITS_PER_DEGREE = 100_000.0

        fun decode(bytes: ByteArray): Position {
            val m = ProtoMessage.parse(bytes)
            return Position(
                latitudeI = m.int32OrNull(1),
                longitudeI = m.int32OrNull(2),
                altitude = m.int32OrNull(3),
                time = m.uint32(4),
                locationSource = m.int32(5),
                timestamp = m.uint32(7),
                altitudeHae = m.sint32OrNull(9),
                groundSpeed = m.uint32OrNull(15),
                groundTrack = m.uint32OrNull(16),
                satsInView = m.int32(19),
                seqNumber = m.uint32(22),
                precisionBits = m.int32(23),
            )
        }
    }
}

/** `User` (node owner). */
class User(
    val id: String = "",                        // 1
    val longName: String = "",                  // 2
    val shortName: String = "",                 // 3
    val hwModel: Int = 0,                       // 5 enum HardwareModel
    val isLicensed: Boolean = false,            // 6
    val role: Int = 0,                          // 7 enum Config.DeviceConfig.Role
    val publicKey: ByteArray = ByteArray(0),    // 8
) {
    fun encode(): ByteArray = ProtoWriter()
        .string(1, id).string(2, longName).string(3, shortName)
        .uint(5, hwModel.toLong()).bool(6, isLicensed).uint(7, role.toLong()).bytes(8, publicKey)
        .toByteArray()

    companion object {
        const val HW_MODEL_HELTEC_V3 = 43

        fun decode(bytes: ByteArray): User {
            val m = ProtoMessage.parse(bytes)
            return User(m.string(1), m.string(2), m.string(3), m.int32(5), m.bool(6), m.int32(7), m.bytes(8))
        }
    }
}

/** `Data` – the decoded payload of a MeshPacket. */
class MeshData(
    val portnum: Int,                           // 1 enum PortNum
    val payload: ByteArray = ByteArray(0),      // 2
    val wantResponse: Boolean = false,          // 3
    val dest: Long = 0,                         // 4 fixed32
    val source: Long = 0,                       // 5 fixed32
    val requestId: Long = 0,                    // 6 fixed32
    val replyId: Long = 0,                      // 7 fixed32
    val emoji: Long = 0,                        // 8 fixed32
) {
    fun encode(): ByteArray = ProtoWriter()
        .uint(1, portnum.toLong()).bytes(2, payload).bool(3, wantResponse)
        .fixed32(4, u32(dest)).fixed32(5, u32(source)).fixed32(6, u32(requestId))
        .fixed32(7, u32(replyId)).fixed32(8, u32(emoji))
        .toByteArray()

    companion object {
        /** nanopb max_size of Data.payload. */
        const val MAX_PAYLOAD = 233

        fun decode(bytes: ByteArray): MeshData {
            val m = ProtoMessage.parse(bytes)
            return MeshData(
                m.int32(1), m.bytes(2), m.bool(3), m.uint32(4), m.uint32(5), m.uint32(6), m.uint32(7), m.uint32(8),
            )
        }
    }
}

/** `MeshPacket`. When sent from the phone, [from] is left 0 and the radio fills in its own number. */
class MeshPacket(
    val from: Long = 0,                         // 1 fixed32
    val to: Long = NodeNum.BROADCAST,           // 2 fixed32
    val channel: Int = 0,                       // 3 uint32 (channel index)
    val decoded: MeshData? = null,              // 4 oneof payload_variant
    val encrypted: ByteArray? = null,           // 5 oneof payload_variant
    val id: Long = 0,                           // 6 fixed32
    val rxTime: Long? = null,                   // 7 optional fixed32, epoch seconds
    val rxSnr: Float = 0f,                      // 8 float
    val hopLimit: Int = 0,                      // 9 uint32
    val wantAck: Boolean = false,               // 10 bool
    val priority: Int = 0,                      // 11 enum Priority
    val rxRssi: Int? = null,                    // 12 optional int32
    val viaMqtt: Boolean = false,               // 14 bool
    val hopStart: Int = 0,                      // 15 uint32
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        fixed32(1, u32(from))
        fixed32(2, u32(to))
        uint(3, channel.toLong())
        decoded?.let { message(4, it.encode()) }
        if (decoded == null) encrypted?.let { bytesAlways(5, it) }
        fixed32(6, u32(id))
        rxTime?.let { fixed32Always(7, u32(it)) }
        float(8, rxSnr)
        uint(9, hopLimit.toLong())
        bool(10, wantAck)
        uint(11, priority.toLong())
        rxRssi?.let { int32Always(12, it) }
        bool(14, viaMqtt)
        uint(15, hopStart.toLong())
    }.toByteArray()

    companion object {
        /** MeshPacket.Priority values. */
        const val PRIORITY_BACKGROUND = 10
        const val PRIORITY_DEFAULT = 64
        const val PRIORITY_RELIABLE = 70

        fun decode(bytes: ByteArray): MeshPacket {
            val m = ProtoMessage.parse(bytes)
            val variant = m.oneofCase(4, 5)
            return MeshPacket(
                from = m.uint32(1),
                to = m.uint32(2),
                channel = m.int32(3),
                decoded = if (variant == 4) m.messageBytes(4)?.let { MeshData.decode(it) } else null,
                encrypted = if (variant == 5) m.bytes(5) else null,
                id = m.uint32(6),
                rxTime = m.uint32OrNull(7),
                rxSnr = m.float(8),
                hopLimit = m.int32(9),
                wantAck = m.bool(10),
                priority = m.int32(11),
                rxRssi = m.int32OrNull(12),
                viaMqtt = m.bool(14),
                hopStart = m.int32(15),
            )
        }
    }
}

/** `NodeInfo` – one entry of the radio's node DB. */
class NodeInfo(
    val num: Long,                              // 1 uint32
    val user: User? = null,                     // 2
    val position: Position? = null,             // 3
    val snr: Float = 0f,                        // 4 float
    val lastHeard: Long = 0,                    // 5 fixed32, epoch seconds
    val channel: Int = 0,                       // 7 uint32
    val viaMqtt: Boolean = false,               // 8 bool
    val hopsAway: Int? = null,                  // 9 optional uint32
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        uint(1, num)
        user?.let { message(2, it.encode()) }
        position?.let { message(3, it.encode()) }
        float(4, snr)
        fixed32(5, u32(lastHeard))
        uint(7, channel.toLong())
        bool(8, viaMqtt)
        hopsAway?.let { varintAlways(9, it.toLong()) }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): NodeInfo {
            val m = ProtoMessage.parse(bytes)
            return NodeInfo(
                num = m.uint32(1),
                user = m.messageBytes(2)?.let { User.decode(it) },
                position = m.messageBytes(3)?.let { Position.decode(it) },
                snr = m.float(4),
                lastHeard = m.uint32(5),
                channel = m.int32(7),
                viaMqtt = m.bool(8),
                hopsAway = m.int32OrNull(9),
            )
        }
    }
}

/** `MyNodeInfo`. */
class MyNodeInfo(
    val myNodeNum: Long,                        // 1 uint32
    val rebootCount: Long = 0,                  // 8 uint32
    val minAppVersion: Long = 0,                // 11 uint32
    val pioEnv: String = "",                    // 13 string (e.g. "heltec-v3")
) {
    fun encode(): ByteArray = ProtoWriter()
        .uint(1, myNodeNum).uint(8, rebootCount).uint(11, minAppVersion).string(13, pioEnv).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): MyNodeInfo {
            val m = ProtoMessage.parse(bytes)
            return MyNodeInfo(m.uint32(1), m.uint32(8), m.uint32(11), m.string(13))
        }
    }
}

/** `DeviceMetadata` (only what we display). */
class DeviceMetadata(
    val firmwareVersion: String = "",           // 1
    val hwModel: Int = 0,                       // 9
) {
    fun encode(): ByteArray = ProtoWriter().string(1, firmwareVersion).uint(9, hwModel.toLong()).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): DeviceMetadata {
            val m = ProtoMessage.parse(bytes)
            return DeviceMetadata(m.string(1), m.int32(9))
        }
    }
}

/** `ToRadio` – one message from the app to the radio (exactly one variant set). */
sealed interface ToRadio {
    fun encode(): ByteArray

    data class Packet(val packet: MeshPacket) : ToRadio {
        override fun encode() = ProtoWriter().message(1, packet.encode()).toByteArray()
    }

    data class WantConfig(val nonce: Long) : ToRadio {
        override fun encode() = ProtoWriter().varintAlways(3, nonce).toByteArray()
    }

    data object Disconnect : ToRadio {
        override fun encode() = ProtoWriter().boolAlways(4, true).toByteArray()
    }

    data class Heartbeat(val nonce: Long = 0) : ToRadio {
        override fun encode() = ProtoWriter().message(7, ProtoWriter().uint(1, nonce).toByteArray()).toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): ToRadio {
            val m = ProtoMessage.parse(bytes)
            return when (m.oneofCase(1, 3, 4, 7)) {
                1 -> Packet(MeshPacket.decode(m.messageBytes(1)!!))
                3 -> WantConfig(m.uint32(3))
                4 -> Disconnect
                7 -> Heartbeat(m.message(7)!!.uint32(1))
                else -> throw ProtoException("ToRadio without a supported variant")
            }
        }
    }
}

/** `FromRadio` – one message from the radio. Variants we don't use decode to [Other]. */
sealed interface FromRadio {
    /** FromRadio.id (field 1); a sequence number, 0 if unset. */
    val id: Long

    data class Packet(override val id: Long, val packet: MeshPacket) : FromRadio
    data class MyInfo(override val id: Long, val info: MyNodeInfo) : FromRadio
    data class Node(override val id: Long, val node: NodeInfo) : FromRadio
    data class ConfigMsg(override val id: Long, val config: Config) : FromRadio
    data class LogRecord(override val id: Long, val message: String) : FromRadio
    data class ConfigCompleteId(override val id: Long, val nonce: Long) : FromRadio
    data class Rebooted(override val id: Long) : FromRadio
    data class ChannelMsg(override val id: Long, val channel: Channel) : FromRadio
    data class Metadata(override val id: Long, val metadata: DeviceMetadata) : FromRadio
    data class ClientNotification(override val id: Long, val message: String) : FromRadio
    data class Other(override val id: Long, val field: Int?) : FromRadio

    companion object {
        fun encode(msg: FromRadio): ByteArray {
            val w = ProtoWriter().uint(1, msg.id)
            when (msg) {
                is Packet -> w.message(2, msg.packet.encode())
                is MyInfo -> w.message(3, msg.info.encode())
                is Node -> w.message(4, msg.node.encode())
                is ConfigMsg -> w.message(5, msg.config.encode())
                is LogRecord -> w.message(6, ProtoWriter().string(1, msg.message).toByteArray())
                is ConfigCompleteId -> w.varintAlways(7, msg.nonce)
                is Rebooted -> w.boolAlways(8, true)
                is ChannelMsg -> w.message(10, msg.channel.encode())
                is Metadata -> w.message(13, msg.metadata.encode())
                is ClientNotification -> w.message(16, ProtoWriter().string(4, msg.message).toByteArray())
                is Other -> msg.field?.let { w.message(it, ByteArray(0)) }
            }
            return w.toByteArray()
        }

        fun decode(bytes: ByteArray): FromRadio {
            val m = ProtoMessage.parse(bytes)
            val id = m.uint32(1)
            val case = m.oneofCase(*(2..19).toList().toIntArray())
            return when (case) {
                2 -> Packet(id, MeshPacket.decode(m.messageBytes(2)!!))
                3 -> MyInfo(id, MyNodeInfo.decode(m.messageBytes(3)!!))
                4 -> Node(id, NodeInfo.decode(m.messageBytes(4)!!))
                5 -> ConfigMsg(id, Config.decode(m.messageBytes(5)!!))
                6 -> LogRecord(id, m.message(6)!!.string(1))
                7 -> ConfigCompleteId(id, m.uint32(7))
                8 -> Rebooted(id)
                10 -> ChannelMsg(id, Channel.decode(m.messageBytes(10)!!))
                13 -> Metadata(id, DeviceMetadata.decode(m.messageBytes(13)!!))
                16 -> ClientNotification(id, m.message(16)!!.string(4))
                else -> Other(id, case)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// channel.proto
// ---------------------------------------------------------------------------------------------

/** `ModuleSettings` (per channel). */
class ModuleSettings(
    val positionPrecision: Int = 0,             // 1 uint32
    val isMuted: Boolean = false,               // 2 bool
) {
    fun encode(): ByteArray = ProtoWriter().uint(1, positionPrecision.toLong()).bool(2, isMuted).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ModuleSettings {
            val m = ProtoMessage.parse(bytes)
            return ModuleSettings(m.int32(1), m.bool(2))
        }
    }
}

/** `ChannelSettings`. psk: 0 bytes = none, 1 byte = shorthand, 16 = AES128, 32 = AES256. */
class ChannelSettings(
    val psk: ByteArray = ByteArray(0),          // 2 bytes
    val name: String = "",                      // 3 string
    val id: Long = 0,                           // 4 fixed32
    val uplinkEnabled: Boolean = false,         // 5
    val downlinkEnabled: Boolean = false,       // 6
    val moduleSettings: ModuleSettings? = null, // 7
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        bytes(2, psk); string(3, name); fixed32(4, u32(id)); bool(5, uplinkEnabled); bool(6, downlinkEnabled)
        moduleSettings?.let { message(7, it.encode()) }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ChannelSettings {
            val m = ProtoMessage.parse(bytes)
            return ChannelSettings(
                m.bytes(2), m.string(3), m.uint32(4), m.bool(5), m.bool(6),
                m.messageBytes(7)?.let { ModuleSettings.decode(it) },
            )
        }
    }
}

/** `Channel`. */
class Channel(
    val index: Int,                             // 1 int32
    val settings: ChannelSettings? = null,      // 2
    val role: Int = ROLE_DISABLED,              // 3 enum Role
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        int32(1, index)
        settings?.let { message(2, it.encode()) }
        uint(3, role.toLong())
    }.toByteArray()

    companion object {
        const val ROLE_DISABLED = 0
        const val ROLE_PRIMARY = 1
        const val ROLE_SECONDARY = 2

        fun decode(bytes: ByteArray): Channel {
            val m = ProtoMessage.parse(bytes)
            return Channel(m.int32(1), m.messageBytes(2)?.let { ChannelSettings.decode(it) }, m.int32(3))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// config.proto
// ---------------------------------------------------------------------------------------------

/** `Config.DeviceConfig` (role only; everything else preserved in [unknown]). */
class DeviceConfig(
    val role: Int = ROLE_CLIENT,                // 1 enum Role
    val nodeInfoBroadcastSecs: Long = 0,        // 7 uint32
    val unknown: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = ProtoWriter().uint(1, role.toLong()).uint(7, nodeInfoBroadcastSecs).raw(unknown).toByteArray()

    companion object {
        const val ROLE_CLIENT = 0
        const val ROLE_CLIENT_MUTE = 1
        const val ROLE_TRACKER = 5

        fun decode(bytes: ByteArray): DeviceConfig {
            val m = ProtoMessage.parse(bytes)
            return DeviceConfig(m.int32(1), m.uint32(7), m.unknownFields(setOf(1, 7)))
        }
    }
}

/** `Config.PositionConfig`. */
class PositionConfig(
    val positionBroadcastSecs: Long = 0,        // 1 uint32
    val positionBroadcastSmartEnabled: Boolean = false, // 2 bool
    val fixedPosition: Boolean = false,         // 3 bool
    val gpsUpdateInterval: Long = 0,            // 5 uint32
    val positionFlags: Long = 0,                // 7 uint32
    val broadcastSmartMinimumDistance: Long = 0, // 10 uint32
    val broadcastSmartMinimumIntervalSecs: Long = 0, // 11 uint32
    val gpsMode: Int = GPS_DISABLED,            // 13 enum GpsMode
    val unknown: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = ProtoWriter()
        .uint(1, positionBroadcastSecs).bool(2, positionBroadcastSmartEnabled).bool(3, fixedPosition)
        .uint(5, gpsUpdateInterval).uint(7, positionFlags).uint(10, broadcastSmartMinimumDistance)
        .uint(11, broadcastSmartMinimumIntervalSecs).uint(13, gpsMode.toLong()).raw(unknown)
        .toByteArray()

    companion object {
        const val GPS_DISABLED = 0
        const val GPS_ENABLED = 1
        const val GPS_NOT_PRESENT = 2

        fun decode(bytes: ByteArray): PositionConfig {
            val m = ProtoMessage.parse(bytes)
            return PositionConfig(
                m.uint32(1), m.bool(2), m.bool(3), m.uint32(5), m.uint32(7), m.uint32(10), m.uint32(11), m.int32(13),
                m.unknownFields(setOf(1, 2, 3, 5, 7, 10, 11, 13)),
            )
        }
    }
}

/** `Config.LoRaConfig`. */
class LoRaConfig(
    val usePreset: Boolean = false,             // 1 bool
    val modemPreset: Int = 0,                   // 2 enum ModemPreset
    val bandwidth: Long = 0,                    // 3 uint32
    val spreadFactor: Long = 0,                 // 4 uint32
    val codingRate: Long = 0,                   // 5 uint32
    val frequencyOffset: Float = 0f,            // 6 float
    val region: Int = 0,                        // 7 enum RegionCode
    val hopLimit: Int = 0,                      // 8 uint32
    val txEnabled: Boolean = false,             // 9 bool
    val txPower: Int = 0,                       // 10 int32
    val channelNum: Long = 0,                   // 11 uint32 (frequency slot; 0 = hash of primary channel name)
    val overrideDutyCycle: Boolean = false,     // 12 bool
    val sx126xRxBoostedGain: Boolean = false,   // 13 bool
    val overrideFrequency: Float = 0f,          // 14 float
    val unknown: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = ProtoWriter()
        .bool(1, usePreset).uint(2, modemPreset.toLong()).uint(3, bandwidth).uint(4, spreadFactor)
        .uint(5, codingRate).float(6, frequencyOffset).uint(7, region.toLong()).uint(8, hopLimit.toLong())
        .bool(9, txEnabled).int32(10, txPower).uint(11, channelNum).bool(12, overrideDutyCycle)
        .bool(13, sx126xRxBoostedGain).float(14, overrideFrequency).raw(unknown)
        .toByteArray()

    companion object {
        const val REGION_UNSET = 0
        const val REGION_US = 1
        const val PRESET_LONG_FAST = 0
        const val PRESET_MEDIUM_SLOW = 3
        const val PRESET_MEDIUM_FAST = 4
        const val PRESET_SHORT_FAST = 6

        fun decode(bytes: ByteArray): LoRaConfig {
            val m = ProtoMessage.parse(bytes)
            return LoRaConfig(
                m.bool(1), m.int32(2), m.uint32(3), m.uint32(4), m.uint32(5), m.float(6), m.int32(7), m.int32(8),
                m.bool(9), m.int32(10), m.uint32(11), m.bool(12), m.bool(13), m.float(14),
                m.unknownFields((1..14).toSet()),
            )
        }
    }
}

/** `Config.BluetoothConfig`. */
class BluetoothConfig(
    val enabled: Boolean = false,               // 1 bool
    val mode: Int = MODE_RANDOM_PIN,            // 2 enum PairingMode
    val fixedPin: Long = 0,                     // 3 uint32
    val unknown: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = ProtoWriter().bool(1, enabled).uint(2, mode.toLong()).uint(3, fixedPin).raw(unknown).toByteArray()

    companion object {
        const val MODE_RANDOM_PIN = 0
        const val MODE_FIXED_PIN = 1
        const val MODE_NO_PIN = 2

        fun decode(bytes: ByteArray): BluetoothConfig {
            val m = ProtoMessage.parse(bytes)
            return BluetoothConfig(m.bool(1), m.int32(2), m.uint32(3), m.unknownFields(setOf(1, 2, 3)))
        }
    }
}

/**
 * `Config` – a oneof of the sections. Exactly one of the typed sections is set,
 * or [otherField]/[otherBytes] for sections this app does not model.
 */
class Config private constructor(
    val device: DeviceConfig? = null,           // 1
    val position: PositionConfig? = null,       // 2
    val lora: LoRaConfig? = null,               // 6
    val bluetooth: BluetoothConfig? = null,     // 7
    val otherField: Int? = null,
    val otherBytes: ByteArray? = null,
) {
    constructor(device: DeviceConfig) : this(device = device, position = null)
    constructor(position: PositionConfig) : this(device = null, position = position)
    constructor(lora: LoRaConfig) : this(device = null, lora = lora)
    constructor(bluetooth: BluetoothConfig) : this(device = null, bluetooth = bluetooth)

    fun encode(): ByteArray = ProtoWriter().apply {
        when {
            device != null -> message(1, device.encode())
            position != null -> message(2, position.encode())
            lora != null -> message(6, lora.encode())
            bluetooth != null -> message(7, bluetooth.encode())
            otherField != null -> message(otherField, otherBytes ?: ByteArray(0))
        }
    }.toByteArray()

    companion object {
        /** AdminMessage.ConfigType values for get_config_request. */
        const val TYPE_DEVICE = 0
        const val TYPE_POSITION = 1
        const val TYPE_LORA = 5
        const val TYPE_BLUETOOTH = 6

        fun decode(bytes: ByteArray): Config {
            val m = ProtoMessage.parse(bytes)
            return when (val case = m.oneofCase(*(1..10).toList().toIntArray())) {
                1 -> Config(DeviceConfig.decode(m.messageBytes(1)!!))
                2 -> Config(PositionConfig.decode(m.messageBytes(2)!!))
                6 -> Config(LoRaConfig.decode(m.messageBytes(6)!!))
                7 -> Config(BluetoothConfig.decode(m.messageBytes(7)!!))
                null -> Config(otherField = null)
                else -> Config(otherField = case, otherBytes = m.messageBytes(case))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// admin.proto
// ---------------------------------------------------------------------------------------------

/**
 * `AdminMessage` (payload of ADMIN_APP packets), the variants ZepDriver uses.
 *
 * Session passkey: admin.proto says clients must echo the `session_passkey`
 * (field 101) from a get_x_response in every set_x command. The firmware only
 * enforces that for admin messages arriving from *other* nodes: in
 * AdminModule::handleReceivedProtobuf (firmware v2.7.26) the passkey check is
 * guarded by `mp.from != 0`, and packets written by the locally connected
 * client (BLE/serial) arrive with `from == 0`. So provisioning our own radio
 * needs no passkey, provided we leave MeshPacket.from unset. (Local admin is
 * refused only when `security.is_managed` is set, which ZepDriver never sets.)
 */
sealed interface AdminMessage {
    val sessionPasskey: ByteArray

    fun encode(): ByteArray {
        val w = ProtoWriter()
        when (this) {
            is GetChannelRequest -> w.varintAlways(1, index.toLong() + 1) // 1-based on the wire
            is GetOwnerRequest -> w.boolAlways(3, true)
            is GetConfigRequest -> w.varintAlways(5, configType.toLong())
            is SetOwner -> w.message(32, user.encode())
            is SetChannel -> w.message(33, channel.encode())
            is SetConfig -> w.message(34, config.encode())
            is BeginEditSettings -> w.boolAlways(64, true)
            is CommitEditSettings -> w.boolAlways(65, true)
            is RebootSeconds -> w.varintAlways(97, seconds.toLong())
            is Other -> w.message(field, ByteArray(0))
        }
        w.bytes(101, sessionPasskey)
        return w.toByteArray()
    }

    /** get_channel_request: the wire value is index + 1. */
    class GetChannelRequest(val index: Int, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class GetOwnerRequest(override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class GetConfigRequest(val configType: Int, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class SetOwner(val user: User, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class SetChannel(val channel: Channel, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class SetConfig(val config: Config, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class BeginEditSettings(override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class CommitEditSettings(override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class RebootSeconds(val seconds: Int, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage
    class Other(val field: Int, override val sessionPasskey: ByteArray = ByteArray(0)) : AdminMessage

    companion object {
        fun decode(bytes: ByteArray): AdminMessage {
            val m = ProtoMessage.parse(bytes)
            val pk = m.bytes(101)
            val case = m.fields.lastOrNull { it.number != 101 }?.number
            return when (case) {
                1 -> GetChannelRequest(m.int32(1) - 1, pk)
                3 -> GetOwnerRequest(pk)
                5 -> GetConfigRequest(m.int32(5), pk)
                32 -> SetOwner(User.decode(m.messageBytes(32)!!), pk)
                33 -> SetChannel(Channel.decode(m.messageBytes(33)!!), pk)
                34 -> SetConfig(Config.decode(m.messageBytes(34)!!), pk)
                64 -> BeginEditSettings(pk)
                65 -> CommitEditSettings(pk)
                97 -> RebootSeconds(m.int32(97), pk)
                null -> throw ProtoException("AdminMessage without a variant")
                else -> Other(case, pk)
            }
        }
    }
}
