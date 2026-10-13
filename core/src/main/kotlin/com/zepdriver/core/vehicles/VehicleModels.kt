package com.zepdriver.core.vehicles

import com.zepdriver.core.geo.Xy
import com.zepdriver.core.profile.VehicleProfile
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** How our own vehicle is drawn on the map (Settings → App → My vehicle on the map). */
@Serializable
enum class VehicleModel(val label: String, val description: String) {
    ART_CAR("Art car", "A mutant galleon with a sound tower, LED fin and flame cannons, sized like your car"),
    CAR("Car", "A sedan, about 15 × 6 ft"),
    PICKUP("Pickup truck", "Cab and bed, about 19 × 7 ft"),
    BUS("School bus", "A yellow school bus, about 40 × 8 ft"),
    GOLF_CART("Golf cart", "A seat and a roof on posts, about 8 × 4 ft"),
}

/** What a vehicle prism is made of; the map picks its color. */
enum class VehiclePart {
    /** Art car hull. */
    HULL,
    RED, BLUE, YELLOW, WHITE,
    GLASS, TIRE,
    /** Bumpers, rails, speaker cabinets, posts. */
    TRIM,
    /** Wooden deck, bed liner. */
    DECK,
    /** Headlights, beacons. */
    LIGHT,
    /** Tail lights, stop arm. */
    TAIL,
    /** LED accents. */
    NEON, NEON_ALT,
    FLAME,
}

/**
 * One extruded block of a vehicle model: footprint [outer] (local playa feet,
 * see PlayaData.xy) raised from [baseFt] to [topFt].
 */
class VehiclePrism(val part: VehiclePart, val outer: List<Xy>, val baseFt: Double, val topFt: Double)

/**
 * Blocky 3D models of our own vehicle for the map, built from extruded boxes
 * (MapLibre Native has no 3D model layer). Proportions follow typical
 * vehicles: a sedan's hood/cabin/trunk, a pickup's cab and bed, a
 * conventional (Type C) school bus with its hood, and a golf cart's seat and
 * roof on posts. The art car is a made-up mutant galleon sized from the car
 * profile.
 *
 * Models are exaggerated ([DEFAULT_EXAGGERATION], and at least
 * [MIN_SHOWN_LENGTH_FT] long) so they stay visible at driving zoom, scaled
 * about the phone so the GPS point keeps its place on the vehicle.
 */
object VehicleModels {
    const val DEFAULT_EXAGGERATION = 3.0
    const val MIN_SHOWN_LENGTH_FT = 45.0

    /** Real-world (length, width) in feet; the art car uses the car profile's. */
    fun sizeFt(model: VehicleModel, profile: VehicleProfile): Pair<Double, Double> = when (model) {
        VehicleModel.ART_CAR -> profile.lengthFt.coerceIn(8.0, 120.0) to profile.widthFt.coerceIn(4.0, 40.0)
        VehicleModel.CAR -> 15.0 to 6.0
        VehicleModel.PICKUP -> 19.0 to 7.0
        VehicleModel.BUS -> 40.0 to 8.0
        VehicleModel.GOLF_CART -> 8.0 to 4.0
    }

    /** How much [model] is enlarged on the map. */
    fun scale(model: VehicleModel, profile: VehicleProfile, exaggeration: Double = DEFAULT_EXAGGERATION): Double =
        max(exaggeration, MIN_SHOWN_LENGTH_FT / sizeFt(model, profile).first)

    /**
     * The model's prisms with the phone at [phone] and the vehicle facing
     * [headingDeg] (compass). The phone sits [VehicleProfile.phoneFromFrontFt]
     * behind the front (as a fraction of the profile length for the fixed-size
     * models).
     */
    fun build(
        model: VehicleModel,
        phone: Xy,
        headingDeg: Double,
        profile: VehicleProfile = VehicleProfile(),
        exaggeration: Double = DEFAULT_EXAGGERATION,
    ): List<VehiclePrism> {
        val (l, w) = sizeFt(model, profile)
        val shape = Shape(l, w)
        when (model) {
            VehicleModel.ART_CAR -> shape.artCar()
            VehicleModel.CAR -> shape.car()
            VehicleModel.PICKUP -> shape.pickup()
            VehicleModel.BUS -> shape.bus()
            VehicleModel.GOLF_CART -> shape.golfCart()
        }
        val s = scale(model, profile, exaggeration)
        val frac = if (profile.lengthFt > 0) (profile.phoneFromFrontFt / profile.lengthFt).coerceIn(0.0, 1.0) else 0.0
        val fwd = Xy.fromBearing(headingDeg)
        val right = Xy(fwd.y, -fwd.x)
        val front = phone + fwd * (frac * l * s)
        fun at(u: Double, v: Double) = front + fwd * ((u - l) * s) + right * (v * s)
        return shape.boxes.map { b ->
            VehiclePrism(
                b.part,
                listOf(at(b.u0, b.v0), at(b.u1, b.v0), at(b.u1, b.v1), at(b.u0, b.v1)),
                b.z0 * s,
                b.z1 * s,
            )
        }
    }

    /** A box in the vehicle frame: u along the length (0 = rear), v across (negative = left), z up; feet. */
    private class Box(
        val part: VehiclePart,
        val u0: Double, val u1: Double,
        val v0: Double, val v1: Double,
        val z0: Double, val z1: Double,
    )

    private class Shape(val l: Double, val w: Double) {
        val boxes = ArrayList<Box>()
        val h = w / 2

        fun box(part: VehiclePart, u0: Double, u1: Double, v0: Double, v1: Double, z0: Double, z1: Double) {
            boxes += Box(part, u0, u1, v0, v1, z0, z1)
        }

        /** Centered on the centerline, [halfW] to each side. */
        fun mid(part: VehiclePart, u0: Double, u1: Double, halfW: Double, z0: Double, z1: Double) =
            box(part, u0, u1, -halfW, halfW, z0, z1)

        /** Mirrored left and right, from [vIn] to [vOut] off the centerline. */
        fun pair(part: VehiclePart, u0: Double, u1: Double, vIn: Double, vOut: Double, z0: Double, z1: Double) {
            box(part, u0, u1, -vOut, -vIn, z0, z1)
            box(part, u0, u1, vIn, vOut, z0, z1)
        }

        /** A pair of wheels on an axle at [u], sticking out slightly past the body. */
        fun axle(u: Double, diameter: Double, tireWidth: Double) =
            pair(VehiclePart.TIRE, u - diameter / 2, u + diameter / 2, h - tireWidth, h + 0.15, 0.0, diameter)

        // --- Sedan, ~15 × 6 ft: hood, cabin, trunk -------------------------------------
        fun car() {
            axle(3.0, 2.1, 0.7)
            axle(12.2, 2.1, 0.7)
            mid(VehiclePart.RED, 0.2, 14.8, h - 0.05, 0.9, 2.9) // body: trunk, doors, hood
            mid(VehiclePart.GLASS, 4.2, 10.6, h - 0.4, 2.9, 4.2) // greenhouse
            mid(VehiclePart.RED, 4.8, 9.9, h - 0.35, 4.2, 4.7) // roof
            mid(VehiclePart.TRIM, 14.8, 15.0, h - 0.2, 0.9, 1.7) // bumpers
            mid(VehiclePart.TRIM, 0.0, 0.2, h - 0.2, 0.9, 1.7)
            pair(VehiclePart.LIGHT, 14.8, 15.0, h - 1.4, h - 0.4, 2.0, 2.5)
            pair(VehiclePart.TAIL, 0.0, 0.2, h - 1.2, h - 0.3, 2.0, 2.6)
        }

        // --- Pickup, ~19 × 7 ft: cab and open bed ------------------------------------------
        fun pickup() {
            axle(4.2, 2.7, 0.85)
            axle(15.8, 2.7, 0.85)
            mid(VehiclePart.BLUE, 0.2, 18.8, h - 0.05, 1.3, 3.0) // chassis and lower body
            mid(VehiclePart.DECK, 0.45, 8.6, h - 0.4, 3.0, 3.25) // bed floor
            pair(VehiclePart.BLUE, 0.2, 8.8, h - 0.4, h - 0.05, 3.0, 4.6) // bed sides
            mid(VehiclePart.BLUE, 0.2, 0.45, h - 0.4, 3.0, 4.6) // tailgate
            mid(VehiclePart.BLUE, 8.6, 8.8, h - 0.4, 3.0, 4.6) // front of the bed
            mid(VehiclePart.BLUE, 8.8, 18.8, h - 0.05, 3.0, 4.1) // cab doors and hood
            mid(VehiclePart.GLASS, 9.0, 13.4, h - 0.3, 4.1, 5.7) // cab windows
            mid(VehiclePart.BLUE, 9.2, 13.0, h - 0.25, 5.7, 6.3) // cab roof
            mid(VehiclePart.TRIM, 18.8, 19.0, h - 0.2, 1.2, 2.2) // bumpers
            mid(VehiclePart.TRIM, 0.0, 0.2, h - 0.2, 1.2, 2.0)
            pair(VehiclePart.LIGHT, 18.8, 19.0, h - 1.3, h - 0.4, 3.3, 3.8)
            pair(VehiclePart.TAIL, 0.0, 0.2, h - 0.4, h - 0.05, 3.3, 4.3)
        }

        // --- School bus, ~40 × 8 ft, conventional nose -----------------------------------
        fun bus() {
            axle(9.0, 3.4, 1.0)
            axle(33.5, 3.4, 1.0)
            mid(VehiclePart.YELLOW, 0.3, 36.0, h - 0.05, 1.4, 6.2) // body below the windows
            pair(VehiclePart.TRIM, 0.3, 36.0, h - 0.05, h + 0.05, 3.3, 3.6) // black rub rails
            pair(VehiclePart.TRIM, 0.3, 36.0, h - 0.05, h + 0.05, 4.6, 4.9)
            mid(VehiclePart.GLASS, 0.6, 35.6, h - 0.1, 6.2, 8.6) // window band
            var u = 2.0
            while (u < 34.0) { // pillars between the windows
                pair(VehiclePart.YELLOW, u, u + 0.4, h - 0.1, h, 6.2, 8.6)
                u += 3.2
            }
            mid(VehiclePart.YELLOW, 0.3, 36.0, h - 0.05, 8.6, 10.2) // roof
            mid(VehiclePart.TRIM, 0.3, 36.0, 0.25, 10.2, 10.3) // roof seam, so it reads as a bus from above
            mid(VehiclePart.YELLOW, 36.0, 39.8, h - 0.8, 1.4, 5.2) // hood
            mid(VehiclePart.TRIM, 39.8, 40.0, 2.0, 2.0, 4.6) // grille
            mid(VehiclePart.TRIM, 39.8, 40.0, h - 0.1, 1.0, 2.0) // bumpers
            mid(VehiclePart.TRIM, 0.0, 0.3, h - 0.1, 1.0, 2.0)
            pair(VehiclePart.LIGHT, 39.8, 40.0, 2.3, 3.1, 3.0, 3.8) // headlights
            pair(VehiclePart.LIGHT, 35.0, 35.8, h - 1.4, h - 0.6, 10.2, 10.6) // amber beacons
            pair(VehiclePart.TAIL, 0.5, 1.3, h - 1.4, h - 0.6, 10.2, 10.6) // red beacons
            box(VehiclePart.TAIL, 33.0, 34.2, -(h + 0.9), -(h + 0.05), 5.0, 6.2) // stop arm, driver's side
        }

        // --- Golf cart, ~8 × 4 ft: bench seat, roof on four posts --------------------------
        fun golfCart() {
            axle(1.5, 1.5, 0.55)
            axle(6.5, 1.5, 0.55)
            mid(VehiclePart.WHITE, 0.2, 7.8, h - 0.1, 0.6, 1.9) // body
            mid(VehiclePart.WHITE, 6.2, 7.9, h - 0.2, 1.9, 2.9) // front cowl
            mid(VehiclePart.NEON, 2.6, 4.6, h - 0.25, 1.9, 2.6) // seat
            mid(VehiclePart.NEON, 2.2, 2.6, h - 0.25, 1.9, 3.7) // seat back
            mid(VehiclePart.TRIM, 0.0, 0.6, h - 0.8, 1.9, 3.4) // bag rack
            pair(VehiclePart.TRIM, 1.0, 1.25, h - 0.45, h - 0.2, 1.9, 5.8) // posts
            pair(VehiclePart.TRIM, 6.0, 6.25, h - 0.45, h - 0.2, 2.9, 5.8)
            mid(VehiclePart.NEON_ALT, 0.6, 7.0, h + 0.05, 5.8, 6.2) // roof
            pair(VehiclePart.LIGHT, 7.9, 8.0, 0.9, 1.5, 2.2, 2.6)
        }

        // --- Art car: a mutant galleon ----------------------------------------------------
        fun artCar() {
            // Heights grow with the car's width (a 10 ft wide car has a ~5 ft deck).
            val k = (w / 10.0).coerceIn(0.6, 1.6)
            val wheel = 3.0 * k
            axle(0.17 * l, wheel, 1.0)
            axle(0.83 * l, wheel, 1.0)
            if (l >= 26) axle(0.5 * l, wheel, 1.0)
            // Hull, narrowing and rising toward the bow like a ship's.
            mid(VehiclePart.HULL, 0.0, 0.72 * l, h - 0.2, 1.2 * k, 5.0 * k)
            mid(VehiclePart.HULL, 0.72 * l, 0.84 * l, 0.8 * h, 1.5 * k, 5.4 * k)
            mid(VehiclePart.HULL, 0.84 * l, 0.93 * l, 0.55 * h, 1.9 * k, 5.9 * k)
            mid(VehiclePart.HULL, 0.93 * l, l, 0.28 * h, 2.4 * k, 6.4 * k)
            pair(VehiclePart.NEON, 0.02 * l, 0.72 * l, h - 0.2, h - 0.05, 2.6 * k, 3.1 * k) // LED strip
            pair(VehiclePart.LIGHT, 0.86 * l, 0.9 * l, 0.55 * h, 0.55 * h + 0.2, 4.2 * k, 5.0 * k) // eyes
            // Deck with railings.
            mid(VehiclePart.DECK, 0.02 * l, 0.72 * l, h - 0.45, 5.0 * k, 5.3 * k)
            pair(VehiclePart.TRIM, 0.22 * l, 0.72 * l, h - 0.55, h - 0.3, 7.6 * k, 7.9 * k)
            for (i in 0 until 5) {
                val u = (0.24 + 0.115 * i) * l
                pair(VehiclePart.TRIM, u - 0.2, u + 0.2, h - 0.55, h - 0.3, 5.3 * k, 7.6 * k)
            }
            // DJ booth at the stern, with flame cannons on top.
            mid(VehiclePart.NEON_ALT, 0.02 * l, 0.2 * l, h - 0.5, 5.3 * k, 8.6 * k)
            mid(VehiclePart.TRIM, 0.0, 0.22 * l, h - 0.3, 8.6 * k, 9.0 * k)
            pair(VehiclePart.FLAME, 0.04 * l, 0.04 * l + 0.9, h - 1.4, h - 0.5, 9.0 * k, 11.5 * k)
            // Sound tower: stacked speaker cabinets with LED rings.
            mid(VehiclePart.TRIM, 0.3 * l, 0.4 * l, 0.6 * h, 5.3 * k, 9.0 * k)
            mid(VehiclePart.NEON, 0.31 * l, 0.39 * l, 0.5 * h, 9.0 * k, 9.5 * k)
            mid(VehiclePart.TRIM, 0.31 * l, 0.39 * l, 0.45 * h, 9.5 * k, 12.5 * k)
            mid(VehiclePart.NEON_ALT, 0.32 * l, 0.38 * l, 0.38 * h, 12.5 * k, 13.0 * k)
            mid(VehiclePart.TRIM, 0.325 * l, 0.375 * l, 0.3 * h, 13.0 * k, 15.0 * k)
            // LED dorsal fin, fish-style, rising and falling along the centerline.
            val segments = 5
            for (i in 0 until segments) {
                val u0 = (0.46 + 0.05 * i) * l
                val top = 5.3 + 2.0 + 6.5 * sin(PI * (i + 0.5) / segments)
                mid(if (i % 2 == 0) VehiclePart.NEON_ALT else VehiclePart.NEON, u0, u0 + 0.05 * l, 0.4, 5.3 * k, top * k)
            }
        }
    }
}
