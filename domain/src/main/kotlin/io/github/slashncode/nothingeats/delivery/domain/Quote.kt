package io.github.slashncode.nothingeats.delivery.domain

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How the simulation turns a city into minutes. [timeScale] compresses the delivery: 0.1 makes a
 * 30-minute delivery take 3 real minutes, short enough to watch, long enough to feel real.
 */
data class SimulationSettings(
    val timeScale: Double = 0.1,
    val courierSpeedKmh: Double = 15.0,
    val handoverMinutes: Int = 2,
    /** Couriers on shift; more active deliveries than this add a wait. */
    val couriers: Int = 6,
    val waitPerExtraDeliveryMinutes: Int = 3,
    val maxDistanceMeters: Double = 15_000.0,
) {
    init {
        require(timeScale > 0 && timeScale <= 1) { "timeScale must be in (0, 1]" }
    }

    /** A simulated duration in real time. */
    fun real(simulated: Duration): Duration = simulated * timeScale
}

data class DeliveryQuote(
    val simulatedMinutes: Int,
    val preparationMinutes: Int,
    val distanceMeters: Int,
    val courierShortage: Boolean,
    val realDuration: Duration,
)

object Quotes {
    /** @throws DeliveryOutOfRange when even nothing can't be brought that far. */
    fun calculate(
        pickup: GeoPoint,
        dropOff: GeoPoint,
        preparationMinutes: Int,
        activeDeliveries: Int,
        settings: SimulationSettings,
    ): DeliveryQuote {
        if (preparationMinutes !in
            0..MAX_PREPARATION_MINUTES
        ) {
            throw InvalidDelivery("preparationMinutes", "must be between 0 and $MAX_PREPARATION_MINUTES")
        }
        val distance = pickup.distanceTo(dropOff)
        if (distance > settings.maxDistanceMeters) throw DeliveryOutOfRange(distance.roundToInt())
        val travel = ceil(distance / 1000 / settings.courierSpeedKmh * 60).toInt() + settings.handoverMinutes
        val extra = (activeDeliveries - settings.couriers).coerceAtLeast(0) * settings.waitPerExtraDeliveryMinutes
        val total = preparationMinutes + travel + extra
        return DeliveryQuote(
            simulatedMinutes = total,
            preparationMinutes = preparationMinutes,
            distanceMeters = distance.roundToInt(),
            courierShortage = extra > 0,
            realDuration = settings.real(total.minutes).coerceAtLeast(MIN_REAL_DURATION),
        )
    }

    const val MAX_PREPARATION_MINUTES = 120
    private val MIN_REAL_DURATION = 30.seconds
}
