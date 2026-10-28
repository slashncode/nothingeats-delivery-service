# Events of the delivery service

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/delivery`,
record key and `subject` = the order id (one delivery per order). Consumers must ignore unknown fields.

## `delivery.delivery.v1` (outbox, published with the state change)

| `type` | When | `data` |
|---|---|---|
| `nothingeats.delivery.delivery.status-changed.v1` | Every step: `ACCEPTED`, `COOKING`, `PICKED_UP`, `EN_ROUTE`, `DELIVERED` | `orderId`, `userId`, `status`, `at`, `estimatedArrivalAt`, `restaurantName`, `courier` (`name`, `vehicle`: `BIKE`, `CARGO_BIKE`, `SCOOTER`), `analytics` (the customer's analytics consent at checkout) |

Steps always come in this order, each exactly once; a pod that was down catches up and publishes the
missed steps in order with the same `at`.

## `delivery.courier.v1` (straight to Kafka, retention one hour)

| `type` | When | `data` |
|---|---|---|
| `nothingeats.delivery.courier.moved.v1` | About every two seconds while a delivery is active | `orderId`, `userId`, `latitude`, `longitude`, `status`, `at` |

Positions are not facts anyone relies on: one may be lost, the next replaces it.

## Consumed

- `ordering.order.v1`: `nothingeats.ordering.order.placed.v1` starts a delivery (fields read: `orderId`, `userId`, `restaurant.name/latitude/longitude/preparationMinutes`, `dropOff.latitude/longitude/label`, `quotedSeconds`, `analytics`).
- `identity.user.v1`: `nothingeats.identity.user.deleted.v1` erases the user's deliveries (drop-off locations are personal data).
