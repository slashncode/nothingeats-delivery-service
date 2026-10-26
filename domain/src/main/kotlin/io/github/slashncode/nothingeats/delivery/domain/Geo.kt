package io.github.slashncode.nothingeats.delivery.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) throw InvalidDelivery("location", "is not a place on earth")
    }

    /** Great-circle distance (haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = radians(other.latitude - latitude)
        val dLng = radians(other.longitude - longitude)
        val a = sin(dLat / 2).pow(2) + cos(radians(latitude)) * cos(radians(other.latitude)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }

    /** The point [meters] away in direction [bearingDegrees] (0 = north), flat-earth approximation for city distances. */
    fun offset(
        meters: Double,
        bearingDegrees: Double,
    ): GeoPoint {
        val bearing = radians(bearingDegrees)
        val dLat = meters * cos(bearing) / EARTH_RADIUS_METERS
        val dLng = meters * sin(bearing) / (EARTH_RADIUS_METERS * cos(radians(latitude)))
        return GeoPoint(latitude + degrees(dLat), longitude + degrees(dLng))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0

        fun radians(degrees: Double) = degrees * PI / 180

        fun degrees(radians: Double) = radians * 180 / PI
    }
}

/**
 * A courier's path as a polyline. Positions are interpolated by distance travelled, so the
 * courier moves at an even speed along curves too.
 */
data class Route(
    val points: List<GeoPoint>,
) {
    init {
        require(points.size >= 2) { "A route needs a start and an end" }
    }

    private val cumulative: List<Double> = points.zipWithNext { a, b -> a.distanceTo(b) }.runningFold(0.0) { sum, leg -> sum + leg }

    val lengthMeters: Double get() = cumulative.last()

    /** Where the courier is after [progress] (0 = start, 1 = end) of the way. */
    fun pointAt(progress: Double): GeoPoint {
        val target = progress.coerceIn(0.0, 1.0) * lengthMeters
        val leg = cumulative.indexOfFirst { it >= target }.coerceAtLeast(1)
        val start = cumulative[leg - 1]
        val length = cumulative[leg] - start
        val fraction = if (length == 0.0) 0.0 else (target - start) / length
        val a = points[leg - 1]
        val b = points[leg]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)
    }

    companion object {
        /**
         * A gentle curve from [from] to [to] (quadratic Bézier, control point pushed sideways by
         * [bend] of the distance): straight lines look like a drone, not a bike.
         */
        fun curved(
            from: GeoPoint,
            to: GeoPoint,
            bend: Double,
            steps: Int = 24,
        ): Route {
            val mid = GeoPoint((from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2)
            // Perpendicular to from→to, in degrees (good enough at city scale).
            val control =
                GeoPoint(
                    mid.latitude - (to.longitude - from.longitude) * bend,
                    mid.longitude + (to.latitude - from.latitude) * bend,
                )
            val points =
                (0..steps).map { step ->
                    val t = step.toDouble() / steps
                    val u = 1 - t
                    GeoPoint(
                        u * u * from.latitude + 2 * u * t * control.latitude + t * t * to.latitude,
                        u * u * from.longitude + 2 * u * t * control.longitude + t * t * to.longitude,
                    )
                }
            return Route(points)
        }
    }
}
