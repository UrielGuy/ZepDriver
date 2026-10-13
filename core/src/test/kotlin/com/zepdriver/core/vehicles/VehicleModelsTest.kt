package com.zepdriver.core.vehicles

import com.zepdriver.core.geo.Xy
import com.zepdriver.core.profile.VehicleProfile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VehicleModelsTest {
    private val phone = Xy(1_000.0, -2_000.0)
    private val profile = VehicleProfile(lengthFt = 30.0, widthFt = 10.0, phoneFromFrontFt = 5.0)

    private fun points(prisms: List<VehiclePrism>) = prisms.flatMap { it.outer }

    @Test fun heightsAreSane() {
        for (m in VehicleModel.entries) {
            val prisms = VehicleModels.build(m, phone, 0.0, profile)
            assertTrue(prisms.size >= 8, "$m has ${prisms.size} prisms")
            assertTrue(prisms.all { it.baseFt >= 0 && it.topFt > it.baseFt && it.outer.size == 4 }, "$m")
            assertTrue(prisms.any { it.baseFt == 0.0 && it.part == VehiclePart.TIRE }, "$m stands on wheels")
            val s = VehicleModels.scale(m, profile)
            val top = prisms.maxOf { it.topFt } / s
            assertTrue(top in 4.0..16.0, "$m is $top ft tall")
        }
    }

    @Test fun frontIsAtThePhonePlusItsOffsetFacingNorth() {
        for (m in VehicleModel.entries) {
            val (l, w) = VehicleModels.sizeFt(m, profile)
            val s = VehicleModels.scale(m, profile)
            val pts = points(VehicleModels.build(m, phone, 0.0, profile))
            val frontY = phone.y + profile.phoneFromFrontFt / profile.lengthFt * l * s
            assertEquals(frontY, pts.maxOf { it.y }, 1e-6, "$m front")
            assertEquals(frontY - l * s, pts.minOf { it.y }, 1e-6, "$m rear")
            assertTrue(pts.all { abs(it.x - phone.x) <= (w / 2 + 1.0) * s }, "$m width")
            // Wheels are symmetric about the centerline through the phone.
            val tires = VehicleModels.build(m, phone, 0.0, profile).filter { it.part == VehiclePart.TIRE }
            assertEquals(phone.x, tires.flatMap { it.outer }.map { it.x }.average(), 1e-6, "$m centered")
        }
    }

    @Test fun headingRotatesTheModelAboutThePhone() {
        for (m in VehicleModel.entries) {
            val north = points(VehicleModels.build(m, phone, 0.0, profile))
            val east = points(VehicleModels.build(m, phone, 90.0, profile))
            // Facing east, every point is the north-facing one turned 90° clockwise about the phone.
            for ((a, b) in north.zip(east)) {
                val d = a - phone
                assertEquals(phone.x + d.y, b.x, 1e-6)
                assertEquals(phone.y - d.x, b.y, 1e-6)
            }
            assertTrue(east.maxOf { it.x } > phone.x, "$m faces east")
        }
    }

    @Test fun modelsAreDistinctAndSizedLikeTheRealThing() {
        val signatures = VehicleModel.entries.map { m ->
            val prisms = VehicleModels.build(m, phone, 0.0, profile, exaggeration = 1.0)
            val s = VehicleModels.scale(m, profile, exaggeration = 1.0)
            val pts = points(prisms)
            val length = (pts.maxOf { it.y } - pts.minOf { it.y }) / s
            Triple(Math.round(length), prisms.map { it.part }.toSet(), prisms.size)
        }
        assertEquals(VehicleModel.entries.size, signatures.toSet().size)
        assertEquals(listOf(30L, 15L, 19L, 40L, 8L), signatures.map { it.first })
        // Characteristic parts.
        fun parts(m: VehicleModel) = VehicleModels.build(m, phone, 0.0, profile).map { it.part }.toSet()
        assertTrue(VehiclePart.YELLOW in parts(VehicleModel.BUS))
        assertTrue(VehiclePart.DECK in parts(VehicleModel.PICKUP)) // the bed
        assertTrue(VehiclePart.HULL in parts(VehicleModel.ART_CAR) && VehiclePart.FLAME in parts(VehicleModel.ART_CAR))
    }

    @Test fun artCarFollowsTheProfileAndSmallVehiclesStayVisible() {
        val big = VehicleProfile(lengthFt = 40.0, widthFt = 12.0, phoneFromFrontFt = 0.0)
        val pts = points(VehicleModels.build(VehicleModel.ART_CAR, phone, 0.0, big))
        assertEquals(40.0 * VehicleModels.DEFAULT_EXAGGERATION, pts.maxOf { it.y } - pts.minOf { it.y }, 1e-6)
        assertEquals(phone.y, pts.maxOf { it.y }, 1e-6) // phone at the very front
        assertEquals(3.0, VehicleModels.scale(VehicleModel.BUS, profile))
        val cart = points(VehicleModels.build(VehicleModel.GOLF_CART, phone, 0.0, profile))
        assertTrue(cart.maxOf { it.y } - cart.minOf { it.y } >= VehicleModels.MIN_SHOWN_LENGTH_FT - 1e-6)
    }
}
