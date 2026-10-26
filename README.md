# nothingeats-delivery-service

Fulfillment of [NothingEats](https://github.com/slashncode/nothingeats): a courier for every order that never arrives, simulated in real time. Every step, every courier position and the arrival are real events; only the food is missing.

## How the simulation works

- A placed order (`ordering.order.v1`) gets a courier at once: a name from the shift, a vehicle, a start somewhere 0.7–1.5 km from the restaurant, two curved routes (to the restaurant, then to the customer) and a **plan**: when the kitchen starts, when the courier picks up, rides off and arrives. The plan follows the time the customer was promised at checkout.
- Each row in `deliveries` keeps the plan and a `next_step_at`. A **ticker on every pod** (1 s) locks due rows with `FOR UPDATE SKIP LOCKED`, moves them on and publishes each step through the outbox in the same transaction. Pods share the work; a pod that dies loses nothing, the next tick picks the rows up (record 033).
- The courier's position is a function of time along the routes; every 2 s each active delivery's position goes to `delivery.courier.v1`, claimed by exactly one pod.
- Time runs faster than the clock on the wall: `SIMULATION__TIME_SCALE=0.1` makes a 30-minute delivery take 3 minutes.

## API

| What | Where | Auth |
|---|---|---|
| `QuoteDelivery` (gRPC, `nothingeats.delivery.v1.DeliveryService`, contract in `grpc-api/`) | port 9090, `dns:///delivery-service-grpc:9090` in the cluster | the customer's forwarded token |
| `GET /api/delivery/v1/deliveries/{orderId}` | the plan, both routes, the courier's current position | the customer who ordered (others get 404) |

Quotes: kitchen time + ride (15 km/h) + handover, plus a wait when more deliveries are active than couriers on shift. Drop-offs more than 15 km away are refused (`DELIVERY_OUT_OF_RANGE`).

Events: [docs/events.md](docs/events.md). PostHog server event `delivery_completed` (minutes, distance, vehicle) for the customer.

## Run

```bash
./gradlew build                     # tests need Docker (Postgres, Kafka via Testcontainers)
buf lint && buf breaking --against '.git#branch=main'   # the gRPC contract

export DATABASE__URL=jdbc:postgresql://localhost:15432/delivery DATABASE__USER=delivery DATABASE__PASSWORD=...
./gradlew :app:run --args=migrate
./gradlew :app:run
```

## License

MIT
