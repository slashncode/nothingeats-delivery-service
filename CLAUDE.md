# CLAUDE.md

Delivery service of NothingEats: simulated couriers for placed orders, quotes over gRPC, tracking. Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker for Testcontainers.
- `./gradlew ktlintFormat` — auto-format
- `buf lint` / `buf breaking --against '.git#branch=main'` — the gRPC contract in `grpc-api/src/main/proto`
- `./gradlew :app:run --args=migrate` / `./gradlew :app:run` — needs `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`

## Rules

- Respect the module direction: `domain` ← `application` ← `adapters` ← `app`. Never add a framework dependency to `domain`.
- The proto contract is used by other services' builds (composite build, record 032). Never change a field number or meaning in `v1`; additions only. A breaking change is a new package `nothingeats.delivery.v2`.
- The simulation's state lives in the `deliveries` row (plan + `next_step_at`); never keep timers in memory. Every state change and its event go into one transaction (outbox).
- Positions are fire-and-forget on `delivery.courier.v1`; status changes are facts and always go through the outbox.
- `user.deleted` erases the user's deliveries.
- Wiring happens only in `app/.../ServiceComponent.kt`; lifecycle components, health checks, routes, gRPC services, event handlers and cron tasks are contributed with `@IntoSet`.
