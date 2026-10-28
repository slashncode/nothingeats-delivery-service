package io.github.slashncode.nothingeats.delivery.adapters.messaging

import io.github.slashncode.nothingeats.delivery.application.CourierPosition
import io.github.slashncode.nothingeats.delivery.application.CourierPositions
import io.github.slashncode.nothingeats.delivery.application.DeliveryEvents
import io.github.slashncode.nothingeats.delivery.domain.Delivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import kotlinx.serialization.Serializable
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Serializable
data class CourierV1(
    val name: String,
    val vehicle: String,
)

@Serializable
data class StatusChangedV1(
    val orderId: String,
    val userId: String,
    val status: String,
    val at: String,
    val estimatedArrivalAt: String,
    val restaurantName: String,
    val courier: CourierV1,
    val analytics: Boolean,
)

@Serializable
data class CourierMovedV1(
    val orderId: String,
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val status: String,
    val at: String,
)

/** Status changes through the outbox: published if and only if the transaction that changed the delivery commits. */
class OutboxDeliveryEvents(
    private val outbox: Outbox,
) : DeliveryEvents {
    override suspend fun statusChanged(
        delivery: Delivery,
        status: DeliveryStatus,
        at: Instant,
    ) {
        outbox.enqueue(
            DeliveryTopics.DELIVERIES,
            delivery.orderId,
            DeliveryTopics.STATUS_CHANGED,
            StatusChangedV1(
                orderId = delivery.orderId,
                userId = delivery.userId,
                status = status.name,
                at = at.toString(),
                estimatedArrivalAt = delivery.plan.deliveredAt.toString(),
                restaurantName = delivery.restaurantName,
                courier = CourierV1(delivery.courier.name, delivery.courier.vehicle.name),
                analytics = delivery.analytics,
            ),
            StatusChangedV1.serializer(),
        )
    }
}

/**
 * Positions straight to Kafka, not through the outbox: they are not facts anyone has to rely on,
 * and the next one follows two seconds later. Failures are logged, never retried.
 */
class KafkaCourierPositions(
    private val producer: Producer<String, ByteArray>,
    private val clock: Clock = Clock.systemUTC(),
) : CourierPositions {
    private val log = LoggerFactory.getLogger(KafkaCourierPositions::class.java)

    override suspend fun publish(position: CourierPosition) {
        val event =
            CloudEvent(
                id = UUID.randomUUID().toString(),
                source = DeliveryTopics.SOURCE,
                type = DeliveryTopics.COURIER_MOVED,
                subject = position.orderId,
                time = clock.instant().toString(),
                data =
                    EventJson.encodeToJsonElement(
                        CourierMovedV1.serializer(),
                        CourierMovedV1(
                            position.orderId,
                            position.userId,
                            position.position.latitude,
                            position.position.longitude,
                            position.status.name,
                            position.at.toString(),
                        ),
                    ),
            )
        val record =
            ProducerRecord(
                DeliveryTopics.COURIERS,
                position.orderId,
                EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray(),
            )
        record.headers().add("content-type", CloudEvent.CONTENT_TYPE.toByteArray())
        producer.send(record) { _, error ->
            if (error !=
                null
            ) {
                log.warn("Courier position for {} lost: {}", position.orderId, error.message)
            }
        }
    }
}
