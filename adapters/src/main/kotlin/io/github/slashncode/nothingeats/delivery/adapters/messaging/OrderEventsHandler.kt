package io.github.slashncode.nothingeats.delivery.adapters.messaging

import io.github.slashncode.nothingeats.delivery.application.Location
import io.github.slashncode.nothingeats.delivery.application.OrderToDeliver
import io.github.slashncode.nothingeats.delivery.application.StartDelivery
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import kotlinx.serialization.Serializable

// Tolerant reader of `nothingeats.ordering.order.placed.v1` (contract: ordering-service docs/events.md).

@Serializable
internal data class PlacedRestaurant(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val preparationMinutes: Int,
)

@Serializable
internal data class PlacedDropOff(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
)

@Serializable
internal data class OrderPlaced(
    val orderId: String,
    val userId: String,
    val restaurant: PlacedRestaurant,
    val dropOff: PlacedDropOff,
    val quotedSeconds: Int = 0,
    val analytics: Boolean = false,
)

/** Every placed order gets a courier. Runs in the inbox transaction, so a redelivered order starts nothing twice. */
class OrderEventsHandler(
    private val start: StartDelivery,
) : EventHandler {
    override val topic = "ordering.order.v1"

    override suspend fun handle(event: CloudEvent) {
        if (event.type != PLACED) return
        val order = event.decode(OrderPlaced.serializer())
        start(
            OrderToDeliver(
                orderId = order.orderId,
                userId = order.userId,
                restaurantName = order.restaurant.name,
                pickup = Location(order.restaurant.latitude, order.restaurant.longitude),
                preparationMinutes = order.restaurant.preparationMinutes,
                dropOff = Location(order.dropOff.latitude, order.dropOff.longitude),
                dropOffLabel = order.dropOff.label,
                quotedSeconds = order.quotedSeconds,
                analytics = order.analytics,
            ),
        )
    }

    private companion object {
        const val PLACED = "nothingeats.ordering.order.placed.v1"
    }
}
