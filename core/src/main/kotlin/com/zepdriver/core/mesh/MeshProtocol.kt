package com.zepdriver.core.mesh

import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon

/** Compiled-in network settings shared by every ZepDriver install. */
object ZepMesh {
    /** Primary channel name; also determines the LoRa frequency slot. */
    const val CHANNEL_NAME = "ZepDriver"
    /** BLE pairing PIN set during provisioning. */
    const val BLE_PIN = 123456
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
    /** Initial config download finished; [needsProvisioning] if channel/owner differ from ZepDriver's. */
    data class ConfigComplete(val needsProvisioning: Boolean) : MeshEvent
    data class Log(val line: String) : MeshEvent
}

/**
 * Meshtastic client-API protocol (ToRadio / FromRadio protobufs), transport
 * agnostic. BLE carries the protobuf bytes as-is; serial wraps them with
 * [StreamFraming].
 */
class MeshProtocol(private val nowMillis: () -> Long = System::currentTimeMillis) {
    var myNodeNum: Long? = null
        private set

    /** ToRadio asking the node to dump its config and node DB. */
    fun wantConfig(): ByteArray = TODO("mesh agent")

    /** Decodes one FromRadio message. */
    fun handleFromRadio(bytes: ByteArray): List<MeshEvent> = TODO("mesh agent")

    /** ToRadio broadcasting our position on the ZepDriver channel. */
    fun positionPacket(fix: Fix): ByteArray = TODO("mesh agent")

    /** ToRadio broadcasting a text message on the ZepDriver channel. */
    fun textPacket(text: String): ByteArray = TODO("mesh agent")

    /**
     * ToRadio admin messages (to our own node) that set the owner to
     * [carName], US region, the ZepDriver channel, fixed BLE PIN and position
     * settings, then commit. Send in order after [MeshEvent.MyInfo].
     */
    fun provisioningPackets(carName: String): List<ByteArray> = TODO("mesh agent")

    /** Periodic keepalive. */
    fun heartbeat(): ByteArray = TODO("mesh agent")
}

/** Meshtastic serial framing: 0x94 0xC3, big-endian u16 length, payload. */
object StreamFraming {
    fun frame(payload: ByteArray): ByteArray = TODO("mesh agent")

    /** Splits a byte stream into payloads, skipping debug text between frames. */
    class Deframer {
        fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> = TODO("mesh agent")
    }
}
