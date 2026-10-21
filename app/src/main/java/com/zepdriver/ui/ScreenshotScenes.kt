package com.zepdriver.ui

import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.zepdriver.AppGraph
import com.zepdriver.ThemeMode
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.DestinationKind
import com.zepdriver.core.sim.SimMode
import com.zepdriver.core.sim.SimStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: sets up a scene for the Play Store screenshots, driven by
 * tools/store/screenshots.sh on an emulator:
 *
 *     adb shell am start -n com.zepdriver.debug/com.zepdriver.ui.MainActivity --es zep.screenshot map
 *
 * Uses the playa simulator for the car and hides the debug-only controls, so
 * the screens look like the release app.
 */
object ScreenshotScenes {
    const val EXTRA = "zep.screenshot"

    /** Scene names, in the order the store shows them. */
    val SCENES = listOf("map", "warning", "route", "search", "fleet", "night")

    fun handle(intent: Intent?, vm: MainViewModel) {
        // The simulator only exists in debug builds; release builds ignore the extra.
        if (!vm.sim.available) return
        val scene = intent?.getStringExtra(EXTRA) ?: return
        vm.viewModelScope.launch { run(scene, vm) }
    }

    private suspend fun run(scene: String, vm: MainViewModel) {
        vm.setScreenshotMode(true)
        val bundle = vm.bundle.filterNotNull().first()
        val sim = vm.sim
        vm.updatePrefs(
            vm.prefs.value.copy(
                voicePromptSeen = true,
                voiceEnabled = false,
                themeMode = if (scene == "night") ThemeMode.NIGHT else ThemeMode.DAY,
                showPottyArrow = true,
            ),
        )
        sim.setEnabled(true)
        sim.setFakeFleet(true)
        sim.setMode(SimMode.MANUAL)
        AppGraph.drive.stopNavigation()
        vm.setTab(Tab.DRIVE)
        when (scene) {
            "map" -> sim.setStart(SimStart.OPEN_PLAYA)
            "night" -> sim.setStart(SimStart.DEEP_PLAYA)
            "warning" -> {
                // Too fast on the playa side of the Esplanade: the speed alarm.
                sim.setStart(SimStart.ESPLANADE)
                delay(1_500)
                sim.changeSpeed(7.5)
            }
            // "video": the same drive, recorded for Play's foreground service declaration.
            "route", "video" -> {
                sim.setStart(SimStart.OPEN_PLAYA)
                delay(3_000)
                val data = bundle.data
                val target = data.xy(bundle.addressing.parse("7:30 & E") ?: return)
                val camp = data.camps.indices.minByOrNull { data.campXy[it].distanceTo(target) } ?: return
                val c = data.camps[camp]
                vm.goNow(Destination(c.name, c.position, DestinationKind.CAMP, refId = c.id, address = c.address))
                // Planning waits for the router's grid, which takes a while on an emulator.
                withTimeoutOrNull(150_000) { vm.drive.first { it.nav != null } }
                sim.setMode(SimMode.AUTOPILOT)
            }
            "search" -> {
                sim.setStart(SimStart.OPEN_PLAYA)
                delay(1_500)
                vm.setTab(Tab.SEARCH)
                vm.setQuery("coffee")
            }
            "fleet" -> {
                sim.setStart(SimStart.OPEN_PLAYA)
                delay(4_000)
                vm.setTab(Tab.FLEET)
            }
        }
    }
}
