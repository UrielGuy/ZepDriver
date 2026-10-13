package com.zepdriver.core.alerts

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.rules.RuleSet
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.rules.ZoneKind
import com.zepdriver.core.rules.ZoneShape

/**
 * Every fixed sentence the app speaks for a warning. The monitors build their
 * speech only through these functions, so [catalog] is exactly the set of
 * warning phrases that can be spoken, and the build can pre-record them as
 * audio clips (played even when the phone has no offline text-to-speech).
 * Navigation prompts include camp and street names and stay on the phone's
 * text-to-speech.
 */
object Speech {
    const val SLOW_DOWN = "Slow down"
    const val SLOW_DOWN_ALARM = "Slow down now!"
    const val DAY_LICENSE = "Day license. No driving at night."
    const val NIGHT_LICENSE = "Night license. No driving in daylight."
    const val DIRECT_ROUTE_ONLY = "Direct route to camp only."
    const val PLAYA_ONLY = "Playa-only license. No city driving."
    const val PLAYA_ONLY_ALARM = "Playa-only license. Leave the city by the shortest route."
    const val REROUTING = "Rerouting"

    fun noEntryInside(z: Zone, outside: Boolean): String =
        if (outside) "You're outside ${z.spokenName}. Turn back." else "Get out of ${z.spokenName}"

    fun noEntryAhead(z: Zone): String = "${z.spokenName.capitalized()} ahead. Turn away."

    fun drivingAlong(z: Zone): String = "You're driving on ${z.spokenName}. Get off it."

    fun crossing(z: Zone): String = "Crossing ${z.spokenName}. Don't drive along it."

    fun crossOnlyAhead(z: Zone): String = "${z.spokenName.capitalized()} is cross only. Don't drive along it."

    fun soundOff(z: Zone): String = z.note ?: "Sound off in ${z.spokenName}."

    /** All warning phrases for this year's rules, for every license type and time of day. */
    fun catalog(data: PlayaData, ruleSet: RuleSet): List<String> {
        val out = linkedSetOf(SLOW_DOWN, SLOW_DOWN_ALARM, DAY_LICENSE, NIGHT_LICENSE)
        for (z in ruleSet.zones(data)) when (z.kind) {
            ZoneKind.NO_ENTRY -> {
                out += noEntryInside(z, outside = z.shapes.any { it is ZoneShape.Outside })
                out += noEntryAhead(z)
            }
            ZoneKind.CROSS_ONLY -> {
                out += drivingAlong(z)
                out += crossing(z)
                out += crossOnlyAhead(z)
            }
            ZoneKind.LICENSE_RESTRICTED -> {
                out += DIRECT_ROUTE_ONLY
                out += PLAYA_ONLY
                out += PLAYA_ONLY_ALARM
            }
            ZoneKind.SOUND_OFF -> out += soundOff(z)
            ZoneKind.CAUTION_AREA -> Unit // banner only, never spoken
        }
        return out.toList()
    }

    private fun String.capitalized() = replaceFirstChar { it.uppercaseChar() }
}
