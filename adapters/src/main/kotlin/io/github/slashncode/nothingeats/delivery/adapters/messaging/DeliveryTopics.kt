package io.github.slashncode.nothingeats.delivery.adapters.messaging

/** Contract: docs/events.md. */
object DeliveryTopics {
    const val DELIVERIES = "delivery.delivery.v1"
    const val COURIERS = "delivery.courier.v1"
    const val STATUS_CHANGED = "nothingeats.delivery.delivery.status-changed.v1"
    const val COURIER_MOVED = "nothingeats.delivery.courier.moved.v1"
    const val SOURCE = "/nothingeats/delivery"
}
