package io.github.slashncode.nothingeats.delivery.application

import io.github.slashncode.nothingeats.delivery.domain.Delivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.GeoPoint
import java.time.Instant

interface Deliveries {
    suspend fun find(orderId: String): Delivery?

    /** False when a delivery for this order exists already (the order event came twice). */
    suspend fun insert(delivery: Delivery): Boolean

    suspend fun countActive(): Int

    /**
     * Locks up to [limit] deliveries whose next step is due at [now] (rows other pods hold are
     * skipped) and stores what [advance] makes of them, in one transaction with whatever
     * [advance] publishes. Returns how many were advanced.
     */
    suspend fun advanceDue(
        now: Instant,
        limit: Int,
        advance: suspend (Delivery) -> Delivery,
    ): Int

    /**
     * Active deliveries whose courier position was last published before [before], claimed for
     * this pod by setting their publish time to [now]: each position goes out once per interval.
     */
    suspend fun claimPositions(
        now: Instant,
        before: Instant,
        limit: Int,
    ): List<Delivery>

    /** All deliveries of a user, for `user.deleted`. */
    suspend fun deleteByUser(userId: String): Int
}

/** `delivery.delivery.v1` through the outbox: call inside the transaction that changed the delivery. */
interface DeliveryEvents {
    suspend fun statusChanged(
        delivery: Delivery,
        status: DeliveryStatus,
        at: Instant,
    )
}

data class CourierPosition(
    val orderId: String,
    val userId: String,
    val position: GeoPoint,
    val status: DeliveryStatus,
    val at: Instant,
)

/** `delivery.courier.v1`: fire-and-forget, a lost position is replaced two seconds later. */
interface CourierPositions {
    suspend fun publish(position: CourierPosition)
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
