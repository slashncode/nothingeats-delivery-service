package io.github.slashncode.nothingeats.delivery.adapters.persistence

import io.github.slashncode.nothingeats.delivery.application.Deliveries
import io.github.slashncode.nothingeats.delivery.application.Transactions
import io.github.slashncode.nothingeats.delivery.domain.Courier
import io.github.slashncode.nothingeats.delivery.domain.Delivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryPlan
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.GeoPoint
import io.github.slashncode.nothingeats.delivery.domain.Route
import io.github.slashncode.nothingeats.delivery.domain.Vehicle
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal object DeliveriesTable : Table("deliveries") {
    val orderId = text("order_id")
    val userId = text("user_id")
    val restaurantName = text("restaurant_name")
    val pickupLatitude = double("pickup_latitude")
    val pickupLongitude = double("pickup_longitude")
    val dropOffLatitude = double("drop_off_latitude")
    val dropOffLongitude = double("drop_off_longitude")
    val dropOffLabel = text("drop_off_label").nullable()
    val courierName = text("courier_name")
    val courierVehicle = text("courier_vehicle")
    val approachRoute = array<Double>("approach_route", DoubleColumnType())
    val deliveryRoute = array<Double>("delivery_route", DoubleColumnType())
    val acceptedAt = timestampWithTimeZone("accepted_at")
    val cookingAt = timestampWithTimeZone("cooking_at")
    val pickedUpAt = timestampWithTimeZone("picked_up_at")
    val enRouteAt = timestampWithTimeZone("en_route_at")
    val deliveredAt = timestampWithTimeZone("delivered_at")
    val status = text("status")
    val nextStepAt = timestampWithTimeZone("next_step_at").nullable()
    val positionPublishedAt = timestampWithTimeZone("position_published_at")

    override val primaryKey = PrimaryKey(orderId)
}

class ExposedTransactions(
    private val db: PostgresDatabase,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.transaction { block() }
}

class ExposedDeliveries(
    private val db: PostgresDatabase,
) : Deliveries {
    override suspend fun find(orderId: String): Delivery? =
        db.transaction(readOnly = true) {
            DeliveriesTable
                .selectAll()
                .where { DeliveriesTable.orderId eq orderId }
                .singleOrNull()
                ?.toDelivery()
        }

    override suspend fun insert(delivery: Delivery): Boolean =
        db.transaction {
            DeliveriesTable
                .insertIgnore {
                    it[orderId] = delivery.orderId
                    it[userId] = delivery.userId
                    it[restaurantName] = delivery.restaurantName
                    it[pickupLatitude] = delivery.pickup.latitude
                    it[pickupLongitude] = delivery.pickup.longitude
                    it[dropOffLatitude] = delivery.dropOff.latitude
                    it[dropOffLongitude] = delivery.dropOff.longitude
                    it[dropOffLabel] = delivery.dropOffLabel
                    it[courierName] = delivery.courier.name
                    it[courierVehicle] = delivery.courier.vehicle.name
                    it[approachRoute] = delivery.approach.flat()
                    it[deliveryRoute] = delivery.route.flat()
                    it[acceptedAt] = delivery.plan.acceptedAt.utc()
                    it[cookingAt] = delivery.plan.cookingAt.utc()
                    it[pickedUpAt] = delivery.plan.pickedUpAt.utc()
                    it[enRouteAt] = delivery.plan.enRouteAt.utc()
                    it[deliveredAt] = delivery.plan.deliveredAt.utc()
                    it[status] = delivery.status.name
                    it[nextStepAt] = delivery.next?.second?.utc()
                }.insertedCount > 0
        }

    override suspend fun countActive(): Int =
        db.transaction(readOnly = true) {
            DeliveriesTable
                .selectAll()
                .where { DeliveriesTable.status neq DeliveryStatus.DELIVERED.name }
                .count()
                .toInt()
        }

    override suspend fun advanceDue(
        now: Instant,
        limit: Int,
        advance: suspend (Delivery) -> Delivery,
    ): Int =
        db.transaction {
            val due =
                DeliveriesTable
                    .selectAll()
                    .where { DeliveriesTable.nextStepAt.isNotNull() and (DeliveriesTable.nextStepAt lessEq now.utc()) }
                    .orderBy(DeliveriesTable.nextStepAt to SortOrder.ASC)
                    .limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { it.toDelivery() }
            due.forEach { delivery ->
                val next = advance(delivery)
                DeliveriesTable.update({ DeliveriesTable.orderId eq delivery.orderId }) {
                    it[status] = next.status.name
                    it[nextStepAt] = next.next?.second?.utc()
                }
            }
            due.size
        }

    override suspend fun claimPositions(
        now: Instant,
        before: Instant,
        limit: Int,
    ): List<Delivery> =
        db.transaction {
            val due =
                DeliveriesTable
                    .selectAll()
                    .where {
                        (DeliveriesTable.status inList ACTIVE) and (DeliveriesTable.positionPublishedAt less before.utc())
                    }.limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { it.toDelivery() }
            if (due.isNotEmpty()) {
                DeliveriesTable.update({ DeliveriesTable.orderId inList due.map { it.orderId } }) {
                    it[positionPublishedAt] = now.utc()
                }
            }
            due
        }

    override suspend fun deleteByUser(userId: String): Int =
        db.transaction { DeliveriesTable.deleteWhere { DeliveriesTable.userId eq userId } }

    private companion object {
        val ACTIVE = DeliveryStatus.entries.filter { it != DeliveryStatus.DELIVERED }.map { it.name }
    }
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun Route.flat(): List<Double> = points.flatMap { listOf(it.latitude, it.longitude) }

private fun List<Double>.route(): Route = Route(chunked(2) { (lat, lng) -> GeoPoint(lat, lng) })

private fun ResultRow.toDelivery(): Delivery {
    val t = DeliveriesTable
    return Delivery(
        orderId = this[t.orderId],
        userId = this[t.userId],
        restaurantName = this[t.restaurantName],
        pickup = GeoPoint(this[t.pickupLatitude], this[t.pickupLongitude]),
        dropOff = GeoPoint(this[t.dropOffLatitude], this[t.dropOffLongitude]),
        dropOffLabel = this[t.dropOffLabel],
        courier = Courier(this[t.courierName], Vehicle.valueOf(this[t.courierVehicle])),
        approach = this[t.approachRoute].route(),
        route = this[t.deliveryRoute].route(),
        plan =
            DeliveryPlan(
                acceptedAt = this[t.acceptedAt].toInstant(),
                cookingAt = this[t.cookingAt].toInstant(),
                pickedUpAt = this[t.pickedUpAt].toInstant(),
                enRouteAt = this[t.enRouteAt].toInstant(),
                deliveredAt = this[t.deliveredAt].toInstant(),
            ),
        status = DeliveryStatus.valueOf(this[t.status]),
    )
}
