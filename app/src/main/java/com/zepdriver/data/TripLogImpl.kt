package com.zepdriver.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.content.FileProvider
import com.zepdriver.TripDay
import com.zepdriver.TripLog
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.trip.TrackExport
import com.zepdriver.core.trip.TripPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Our own GPS track in SQLite ("trips.db"), local to the device. */
class TripLogImpl(private val context: Context) : TripLog {

    private val db = Helper(context)

    /** Bumped on every write; [trail] collectors re-query on change. */
    private val revision = MutableStateFlow(0L)
    /** Bumped when the log is cleared, forcing collectors to reload from scratch. */
    @Volatile private var clearGeneration = 0

    /** Appends a point. Blocking; call off the main thread. */
    fun record(point: TripPoint) {
        val values = ContentValues().apply {
            put("time", point.timeMillis)
            put("lat", point.lat)
            put("lon", point.lon)
            put("speed", point.speedMph)
            put("level", point.level.ordinal)
            put("note", point.note)
        }
        db.writableDatabase.insert(TABLE, null, values)
        revision.update { it + 1 }
    }

    override fun trail(sinceMillis: Long): Flow<List<TripPoint>> = flow {
        var loadedGeneration = -1
        var points = ArrayList<TripPoint>()
        revision.collect {
            val gen = clearGeneration
            if (gen != loadedGeneration) {
                points = ArrayList(query("time >= ?", arrayOf(sinceMillis.toString())))
                loadedGeneration = gen
            } else {
                val after = points.lastOrNull()?.timeMillis ?: (sinceMillis - 1)
                val more = query("time > ?", arrayOf(after.toString()))
                if (more.isEmpty()) return@collect
                points.addAll(more)
            }
            emit(points.toList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun days(): List<TripDay> = withContext(Dispatchers.IO) {
        class Acc(var points: Int = 0, var feet: Double = 0.0, var lat: Double = 0.0, var lon: Double = 0.0)
        val byDay = LinkedHashMap<LocalDate, Acc>()
        db.readableDatabase.rawQuery("SELECT time, lat, lon FROM $TABLE ORDER BY time", null).use { c ->
            while (c.moveToNext()) {
                val day = localDate(c.getLong(0))
                val lat = c.getDouble(1)
                val lon = c.getDouble(2)
                val acc = byDay.getOrPut(day) { Acc() }
                if (acc.points > 0) acc.feet += distanceFt(acc.lat, acc.lon, lat, lon)
                acc.points++
                acc.lat = lat
                acc.lon = lon
            }
        }
        byDay.entries.sortedByDescending { it.key }
            .map { (day, acc) -> TripDay(day.toString(), acc.points, acc.feet) }
    }

    override suspend fun export(days: List<String>, kml: Boolean): Uri = withContext(Dispatchers.IO) {
        val dates = days.map { LocalDate.parse(it) }.distinct().sorted()
        require(dates.isNotEmpty()) { "No days selected" }
        val points = dates.flatMap { d ->
            val start = d.atStartOfDay(BRC_ZONE).toInstant().toEpochMilli()
            val end = d.plusDays(1).atStartOfDay(BRC_ZONE).toInstant().toEpochMilli()
            query("time >= ? AND time < ?", arrayOf(start.toString(), end.toString()))
        }
        val label = if (dates.size == 1) "${dates[0]}" else "${dates.first()}_to_${dates.last()}"
        val name = "ZepDriver $label"
        val text = if (kml) TrackExport.kml(name, points) else TrackExport.gpx(name, points)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export
        val file = File(dir, "zepdriver-$label.${if (kml) "kml" else "gpx"}")
        file.writeText(text)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            db.writableDatabase.delete(TABLE, null, null)
            clearGeneration++
            revision.update { it + 1 }
        }
    }

    private fun query(where: String, args: Array<String>): List<TripPoint> =
        db.readableDatabase.rawQuery(
            "SELECT time, lat, lon, speed, level, note FROM $TABLE WHERE $where ORDER BY time", args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toPoint()) } }

    private fun Cursor.toPoint() = TripPoint(
        timeMillis = getLong(0),
        lat = getDouble(1),
        lon = getDouble(2),
        speedMph = getDouble(3),
        level = AlertLevel.entries.getOrElse(getInt(4)) { AlertLevel.NONE },
        note = if (isNull(5)) null else getString(5),
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, "trips.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (time INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, " +
                    "speed REAL NOT NULL, level INTEGER NOT NULL, note TEXT)"
            )
            db.execSQL("CREATE INDEX points_time ON $TABLE(time)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val TABLE = "points"
        /** Black Rock City's time zone; trip days are BRC calendar days. */
        val BRC_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

        fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(BRC_ZONE).toLocalDate()

        /** Great-circle distance in feet. */
        fun distanceFt(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 20_902_231.0 // Earth radius in feet
            val p1 = lat1 * PI / 180
            val p2 = lat2 * PI / 180
            val dp = p2 - p1
            val dl = (lon2 - lon1) * PI / 180
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
