package io.github.slashncode.nothingeats.delivery.application

import io.github.slashncode.nothingeats.delivery.domain.Delivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.GeoPoint
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * One tick of the simulation: every delivery whose next step is due moves on, each step
 * published with the state change in one transaction. Runs on every pod; rows are locked with
 * SKIP LOCKED, so pods share the work instead of doing it twice.
 */
class AdvanceDeliveries(
    private val deliveries: Deliveries,
    private val events: DeliveryEvents,
    private val analytics: ProductAnalytics,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        return deliveries.advanceDue(now, BATCH) { delivery ->
            val (next, steps) = delivery.advance(now)
            steps.forEach { events.statusChanged(next, it, now) }
            if (DeliveryStatus.DELIVERED in steps) {
                analytics.capture(
                    "delivery_completed",
                    mapOf(
                        "order_id" to delivery.orderId,
                        "minutes" to
                            java.time.Duration
                                .between(delivery.plan.acceptedAt, now)
                                .toMinutes(),
                        "distance_meters" to delivery.route.lengthMeters.toLong(),
                        "vehicle" to
                            delivery.courier.vehicle.name
                                .lowercase(),
                    ),
                    distinctId = delivery.userId,
                )
            }
            next
        }
    }

    private companion object {
        const val BATCH = 50
    }
}

/** Every [interval], each active delivery's courier position, published by exactly one pod. */
class PublishCourierPositions(
    private val deliveries: Deliveries,
    private val positions: CourierPositions,
    private val interval: Duration = 2.seconds,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        val due = deliveries.claimPositions(now, now.minus(interval.toJavaDuration()), BATCH)
        due.forEach { positions.publish(CourierPosition(it.orderId, it.userId, it.courierPosition(now), it.status, now)) }
        return due.size
    }

    private companion object {
        const val BATCH = 200
    }
}

data class DeliveryView(
    val delivery: Delivery,
    val courierAt: GeoPoint,
    val at: Instant,
)

/** The tracking page's starting point; live changes come over the notification stream after that. */
class TrackDelivery(
    private val deliveries: Deliveries,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(
        orderId: String,
        userId: String,
    ): DeliveryView {
        // Someone else's order looks exactly like no order.
        val delivery = deliveries.find(orderId)?.takeIf { it.userId == userId } ?: throw notFound()
        val now = clock.instant()
        return DeliveryView(delivery, delivery.courierPosition(now), now)
    }

    private fun notFound() = AppError.NotFound(ErrorCode("DELIVERY_NOT_FOUND"), "There is no delivery for this order (yet)")
}

/** `user.deleted`: drop-off locations are personal data. */
class ForgetUser(
    private val deliveries: Deliveries,
) {
    suspend operator fun invoke(userId: String) = deliveries.deleteByUser(userId)
}
