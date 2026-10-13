package com.zepdriver.mesh

import android.content.Context
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.mesh.MeshText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists the fleet and recent messages so they survive app restarts. */
internal class MeshStore(context: Context) {
    private val sp = context.getSharedPreferences("mesh-data", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val fleetSerializer = ListSerializer(FleetDto.serializer())
    private val textSerializer = ListSerializer(TextDto.serializer())

    @Serializable
    private data class FleetDto(
        val nodeNum: Long,
        val name: String,
        val shortName: String,
        val lat: Double? = null,
        val lon: Double? = null,
        val positionTime: Long? = null,
        val speedMph: Double? = null,
        val headingDeg: Double? = null,
        val lastHeard: Long,
    )

    @Serializable
    private data class TextDto(
        val fromNode: Long,
        val fromName: String,
        val text: String,
        val time: Long,
        val mine: Boolean,
    )

    fun loadFleet(): List<FleetVehicle> = runCatching {
        json.decodeFromString(fleetSerializer, sp.getString(KEY_FLEET, null) ?: return emptyList()).map {
            FleetVehicle(
                nodeNum = it.nodeNum,
                name = it.name,
                shortName = it.shortName,
                position = if (it.lat != null && it.lon != null) LatLon(it.lat, it.lon) else null,
                positionTimeMillis = it.positionTime,
                speedMph = it.speedMph,
                headingDeg = it.headingDeg,
                lastHeardMillis = it.lastHeard,
            )
        }
    }.getOrDefault(emptyList())

    fun loadMessages(): List<MeshText> = runCatching {
        json.decodeFromString(textSerializer, sp.getString(KEY_TEXTS, null) ?: return emptyList()).map {
            MeshText(it.fromNode, it.fromName, it.text, it.time, it.mine)
        }
    }.getOrDefault(emptyList())

    fun save(fleet: List<FleetVehicle>, messages: List<MeshText>) {
        val f = fleet.map {
            FleetDto(
                it.nodeNum, it.name, it.shortName, it.position?.lat, it.position?.lon,
                it.positionTimeMillis, it.speedMph, it.headingDeg, it.lastHeardMillis,
            )
        }
        val t = messages.map { TextDto(it.fromNode, it.fromName, it.text, it.timeMillis, it.mine) }
        sp.edit()
            .putString(KEY_FLEET, json.encodeToString(fleetSerializer, f))
            .putString(KEY_TEXTS, json.encodeToString(textSerializer, t))
            .apply()
    }

    private companion object {
        const val KEY_FLEET = "fleet"
        const val KEY_TEXTS = "messages"
    }
}
