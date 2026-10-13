package com.zepdriver.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class EtaTest {
    private fun state(toStop: Double, total: Double) = NavState(
        plan = RoutePlan(emptyList()), legIndex = 0, nextInstruction = null, distanceToNextFt = 0.0,
        remainingFt = total, offRoute = false, finished = false, speech = null, toStopFt = toStop,
    )

    @Test fun fiveMphIs440FeetPerMinute() {
        assertEquals(10.0, state(4400.0, 9000.0).minutesToStop(), 1e-9)
        assertEquals(1.0, state(440.0, 440.0).minutesToStop(), 1e-9)
    }

    @Test fun etaUsesCurrentStopOnly() {
        assertEquals(2.0, state(880.0, 50_000.0).minutesToStop(), 1e-9)
    }

}
