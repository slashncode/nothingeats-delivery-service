package io.github.slashncode.nothingeats.delivery.application

import io.github.slashncode.nothingeats.delivery.domain.Delivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryDeliveries : Deliveries {
    val rows = ConcurrentHashMap<String, Delivery>()
    private val positionsAt = ConcurrentHashMap<String, Instant>()

    override suspend fun find(orderId: String) = rows[orderId]

    override suspend fun insert(delivery: Delivery) = rows.putIfAbsent(delivery.orderId, delivery) == null

    override suspend fun countActive() = rows.values.count { it.active }

    override suspend fun advanceDue(
        now: Instant,
        limit: Int,
        advance: suspend (Delivery) -> Delivery,
    ): Int {
        val due = rows.values.filter { d -> d.next?.let { it.second <= now } == true }.take(limit)
        due.forEach { rows[it.orderId] = advance(it) }
        return due.size
    }

    override suspend fun claimPositions(
        now: Instant,
        before: Instant,
        limit: Int,
    ): List<Delivery> {
        val due = rows.values.filter { it.active && (positionsAt[it.orderId] ?: Instant.EPOCH) < before }.take(limit)
        due.forEach { positionsAt[it.orderId] = now }
        return due
    }

    override suspend fun deleteByUser(userId: String): Int {
        val mine = rows.values.filter { it.userId == userId }
        mine.forEach { rows.remove(it.orderId) }
        return mine.size
    }
}

class RecordingDeliveryEvents : DeliveryEvents {
    val published = mutableListOf<Pair<String, DeliveryStatus>>()

    override suspend fun statusChanged(
        delivery: Delivery,
        status: DeliveryStatus,
        at: Instant,
    ) {
        published += delivery.orderId to status
    }
}

class RecordingPositions : CourierPositions {
    val published = mutableListOf<CourierPosition>()

    override suspend fun publish(position: CourierPosition) {
        published += position
    }
}

object DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

/** A clock the test moves forward. */
class MovableClock(
    var now: Instant,
) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId) = this

    override fun instant(): Instant = now
}

fun order(
    id: String = "o-1",
    userId: String = "u-1",
    quotedSeconds: Int = 180,
    analytics: Boolean = true,
) = OrderToDeliver(
    orderId = id,
    userId = userId,
    restaurantName = "Phantom Pizza",
    pickup = Location(53.5503, 9.9922),
    preparationMinutes = 15,
    dropOff = Location(53.5636, 9.9654),
    dropOffLabel = "  Zuhause  ",
    quotedSeconds = quotedSeconds,
    analytics = analytics,
)
