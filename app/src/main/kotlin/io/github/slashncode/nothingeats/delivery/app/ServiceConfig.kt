package io.github.slashncode.nothingeats.delivery.app

import io.github.slashncode.nothingeats.delivery.domain.SimulationSettings
import io.github.slashncode.nothingeats.platform.grpc.GrpcServerConfig
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.http.auth.JwtConfig
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.persistence.PostgresConfig
import io.github.slashncode.nothingeats.platform.posthog.PostHogSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the service reads from its environment, validated at startup. Required:
 * `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`. In the cluster also `JWT__JWKS_URL`,
 * `KAFKA__BOOTSTRAP_SERVERS` and `GRPC__PORT`. The simulation: `SIMULATION__TIME_SCALE` (0.1 = a
 * 30-minute delivery takes 3 minutes), `SIMULATION__COURIERS`, …
 */
data class ServiceConfig(
    val http: HttpServerConfig = HttpServerConfig(),
    val grpc: GrpcServerConfig = GrpcServerConfig(),
    val database: PostgresConfig,
    val kafka: KafkaConfig = KafkaConfig(),
    val jwt: JwtConfig = JwtConfig(),
    val posthog: PostHogSettings = PostHogSettings(),
    val simulation: SimulationSettings = SimulationSettings(),
    val ticker: TickerConfig = TickerConfig(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
)

data class TickerConfig(
    val tick: Duration = 1.seconds,
    val positionInterval: Duration = 2.seconds,
)

data class LifecycleConfig(
    /** Time between readiness going down and the shutdown, so load balancers stop sending traffic first. */
    val drainDelay: Duration = 5.seconds,
)
