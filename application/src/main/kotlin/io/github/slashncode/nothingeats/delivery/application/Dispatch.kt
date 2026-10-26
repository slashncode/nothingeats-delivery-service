package io.github.slashncode.nothingeats.delivery.application

import io.github.slashncode.nothingeats.delivery.domain.Courier
import io.github.slashncode.nothingeats.delivery.domain.Delivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryOutOfRange
import io.github.slashncode.nothingeats.delivery.domain.DeliveryPlan
import io.github.slashncode.nothingeats.delivery.domain.DeliveryQuote
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.GeoPoint
import io.github.slashncode.nothingeats.delivery.domain.InvalidDelivery
import io.github.slashncode.nothingeats.delivery.domain.Quotes
import io.github.slashncode.nothingeats.delivery.domain.Route
import io.github.slashncode.nothingeats.delivery.domain.SimulationSettings
import io.github.slashncode.nothingeats.delivery.domain.Vehicle
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** `QuoteDelivery` (gRPC): how long it would take right now, with the current load. */
class QuoteDelivery(
    private val deliveries: Deliveries,
    private val settings: SimulationSettings,
) {
    suspend operator fun invoke(
        pickup: Location,
        dropOff: Location,
        preparationMinutes: Int,
    ): DeliveryQuote =
        validated {
            Quotes.calculate(pickup.point(), dropOff.point(), preparationMinutes, deliveries.countActive(), settings)
        }
}

/** Coordinates as they come in, validated when used. */
data class Location(
    val latitude: Double,
    val longitude: Double,
) {
    fun point() = GeoPoint(latitude, longitude)
}

/** What delivery needs of a placed order (`nothingeats.ordering.order.placed.v1`). */
data class OrderToDeliver(
    val orderId: String,
    val userId: String,
    val restaurantName: String,
    val pickup: Location,
    val preparationMinutes: Int,
    val dropOff: Location,
    val dropOffLabel: String?,
    /** What the customer was promised at checkout; 0 when ordering had no quote (delivery was down). */
    val quotedSeconds: Int,
)

/**
 * A courier takes the order at once: picked from the shift, starting somewhere near the
 * restaurant, with the plan fixed now. The plan follows the promise from checkout, so the
 * delivery arrives when the customer was told. Idempotent per order.
 */
class StartDelivery(
    private val deliveries: Deliveries,
    private val events: DeliveryEvents,
    private val transactions: Transactions,
    private val settings: SimulationSettings,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(StartDelivery::class.java)

    suspend operator fun invoke(order: OrderToDeliver) {
        val now = clock.instant()
        val pickup = order.pickup.point()
        val dropOff = order.dropOff.point()
        // The order is placed; refusing it here would help nobody, so the range limit is the quote's job.
        val quote =
            try {
                Quotes.calculate(pickup, dropOff, order.preparationMinutes, deliveries.countActive(), settings)
            } catch (e: DeliveryOutOfRange) {
                log.warn("Order {} was placed {} m away; delivering anyway", order.orderId, e.distanceMeters)
                Quotes.calculate(pickup, pickup, order.preparationMinutes, 0, settings)
            }
        val total = if (order.quotedSeconds > 0) order.quotedSeconds.seconds else quote.realDuration
        val share = order.preparationMinutes.toDouble() / quote.simulatedMinutes.coerceAtLeast(1)
        val start = pickup.offset(random.nextDouble(APPROACH_MIN_METERS, APPROACH_MAX_METERS), random.nextDouble(0.0, FULL_CIRCLE))
        val delivery =
            Delivery(
                orderId = order.orderId,
                userId = order.userId,
                restaurantName = order.restaurantName,
                pickup = pickup,
                dropOff = dropOff,
                dropOffLabel =
                    order.dropOffLabel
                        ?.trim()
                        ?.take(MAX_LABEL)
                        ?.ifEmpty { null },
                courier = Courier(COURIERS.random(random), Vehicle.entries.random(random)),
                approach = Route.curved(start, pickup, bend = bend()),
                route = Route.curved(pickup, dropOff, bend = bend()),
                plan = DeliveryPlan.from(now, total, share),
                status = DeliveryStatus.ACCEPTED,
            )
        transactions.run {
            if (deliveries.insert(delivery)) events.statusChanged(delivery, DeliveryStatus.ACCEPTED, now)
        }
    }

    private fun bend() = random.nextDouble(MIN_BEND, MAX_BEND) * if (random.nextBoolean()) 1 else -1

    private companion object {
        const val APPROACH_MIN_METERS = 700.0
        const val APPROACH_MAX_METERS = 1_500.0
        const val FULL_CIRCLE = 360.0
        const val MIN_BEND = 0.08
        const val MAX_BEND = 0.25
        const val MAX_LABEL = 80

        // Couriers who deliver nothing, reliably.
        val COURIERS =
            listOf("Nora Nebel", "Gustav Geist", "Lotte Luft", "Paul Phantom", "Mira Mirage", "Casper Kühn", "Ida Illusion", "Theo Trug")
    }
}

internal inline fun <T> validated(block: () -> T): T =
    try {
        block()
    } catch (e: InvalidDelivery) {
        throw AppError.Invalid(
            ErrorCode("DELIVERY_INVALID"),
            "The delivery request is invalid",
            listOf(
                Violation(
                    e.field,
                    e.message ?: "invalid",
                ),
            ),
        )
    } catch (e: DeliveryOutOfRange) {
        throw AppError.Invalid(
            ErrorCode("DELIVERY_OUT_OF_RANGE"),
            e.message ?: "Too far away",
            listOf(Violation("dropOff", "too far away")),
        )
    }
