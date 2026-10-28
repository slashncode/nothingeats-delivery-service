package io.github.slashncode.nothingeats.delivery.domain

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

enum class DeliveryStatus {
    /** A courier took the order and rides to the restaurant. */
    ACCEPTED,

    /** The kitchen cooks; the courier is still on the way to it. */
    COOKING,

    /** The courier waits at the counter. */
    PICKED_UP,

    /** On the way to the customer. */
    EN_ROUTE,

    /** Nothing has arrived. */
    DELIVERED,
}

enum class Vehicle { BIKE, CARGO_BIKE, SCOOTER }

data class Courier(
    val name: String,
    val vehicle: Vehicle,
)

/** When each step happens, fixed when the order comes in: the simulation only follows it. */
data class DeliveryPlan(
    val acceptedAt: Instant,
    val cookingAt: Instant,
    val pickedUpAt: Instant,
    val enRouteAt: Instant,
    val deliveredAt: Instant,
) {
    init {
        require(acceptedAt <= cookingAt && cookingAt <= pickedUpAt && pickedUpAt <= enRouteAt && enRouteAt <= deliveredAt) {
            "Steps must not go back in time"
        }
    }

    fun at(status: DeliveryStatus): Instant =
        when (status) {
            DeliveryStatus.ACCEPTED -> acceptedAt
            DeliveryStatus.COOKING -> cookingAt
            DeliveryStatus.PICKED_UP -> pickedUpAt
            DeliveryStatus.EN_ROUTE -> enRouteAt
            DeliveryStatus.DELIVERED -> deliveredAt
        }

    companion object {
        /**
         * Spreads [total] over the steps: the kitchen gets its share of the quote, the courier
         * waits a moment at the counter, the ride takes the rest.
         */
        fun from(
            start: Instant,
            total: Duration,
            preparationShare: Double,
        ): DeliveryPlan {
            val millis = total.inWholeMilliseconds

            fun after(share: Double) = start.plusMillis((millis * share).toLong())
            val pickup = preparationShare.coerceIn(MIN_SHARE, MAX_SHARE)
            return DeliveryPlan(
                acceptedAt = start,
                cookingAt = after(COOKING_SHARE),
                pickedUpAt = after(pickup),
                enRouteAt = after(pickup + COUNTER_SHARE),
                deliveredAt = start.plus(total.toJavaDuration()),
            )
        }

        private const val COOKING_SHARE = 0.05
        private const val COUNTER_SHARE = 0.04
        private const val MIN_SHARE = 0.2
        private const val MAX_SHARE = 0.7
    }
}

/**
 * One fake delivery, keyed by its order. The [plan] says when each step happens; [status] is how
 * far the simulation has followed it. The courier's position is a function of time: on the
 * [approach] to the restaurant until pickup, on the [route] to the customer after.
 */
data class Delivery(
    val orderId: String,
    val userId: String,
    val restaurantName: String,
    val pickup: GeoPoint,
    val dropOff: GeoPoint,
    val dropOffLabel: String?,
    val courier: Courier,
    val approach: Route,
    val route: Route,
    val plan: DeliveryPlan,
    val status: DeliveryStatus,
    /** The customer's analytics consent at checkout: server-side analytics about this delivery only then. */
    val analytics: Boolean = false,
) {
    val active: Boolean get() = status != DeliveryStatus.DELIVERED

    /** The next step and when it is due, or null when nothing comes anymore. */
    val next: Pair<DeliveryStatus, Instant>?
        get() = DeliveryStatus.entries.getOrNull(status.ordinal + 1)?.let { it to plan.at(it) }

    /** Follows the plan up to [now]: all steps that are due, in order. */
    fun advance(now: Instant): Pair<Delivery, List<DeliveryStatus>> {
        val due = DeliveryStatus.entries.filter { it.ordinal > status.ordinal && plan.at(it) <= now }
        return if (due.isEmpty()) this to emptyList() else copy(status = due.last()) to due
    }

    fun courierPosition(now: Instant): GeoPoint =
        when {
            now < plan.pickedUpAt -> approach.pointAt(progress(plan.acceptedAt, plan.pickedUpAt, now))
            now < plan.enRouteAt -> pickup
            else -> route.pointAt(progress(plan.enRouteAt, plan.deliveredAt, now))
        }

    private fun progress(
        from: Instant,
        to: Instant,
        now: Instant,
    ): Double {
        val total = to.toEpochMilli() - from.toEpochMilli()
        return if (total <= 0) 1.0 else ((now.toEpochMilli() - from.toEpochMilli()).toDouble() / total).coerceIn(0.0, 1.0)
    }
}

class InvalidDelivery(
    val field: String,
    reason: String,
) : Exception(reason)

class DeliveryOutOfRange(
    val distanceMeters: Int,
) : Exception("A drop-off $distanceMeters m away is too far, even for nothing")
