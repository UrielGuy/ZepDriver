package com.zepdriver.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zepdriver.ThemeMode
import kotlinx.coroutines.delay

/**
 * Whether to show the night theme. AUTO follows the ambient light sensor
 * (with hysteresis and dwell, see [LightClassifier]); without a sensor, or
 * before its first reading, it uses [fallbackNight] (sunset/sunrise).
 * DAY / NIGHT are manual overrides.
 */
@Composable
fun rememberNightMode(mode: ThemeMode, fallbackNight: Boolean): Boolean {
    val context = LocalContext.current
    var sensorNight by remember { mutableStateOf<Boolean?>(null) }
    val auto = mode == ThemeMode.AUTO

    LaunchedEffect(auto) {
        if (!auto) {
            sensorNight = null
            return@LaunchedEffect
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@LaunchedEffect
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return@LaunchedEffect
        val classifier = LightClassifier()
        var lastLux = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) lastLux = event.values[0]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        try {
            // The light sensor only reports changes, so re-evaluate on a timer
            // to let the dwell time elapse while the level stays put.
            while (true) {
                if (lastLux >= 0f) sensorNight = classifier.feed(lastLux, SystemClock.elapsedRealtime())
                delay(1_000)
            }
        } finally {
            manager.unregisterListener(listener)
        }
    }

    return when (mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> sensorNight ?: fallbackNight
    }
}
