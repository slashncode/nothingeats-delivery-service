package io.github.slashncode.nothingeats.delivery.app

import io.github.slashncode.nothingeats.delivery.adapters.grpc.DeliveryGrpcService
import io.github.slashncode.nothingeats.delivery.adapters.http.TrackingRoutes
import io.github.slashncode.nothingeats.delivery.adapters.messaging.DeliveryTopics
import io.github.slashncode.nothingeats.delivery.adapters.messaging.IdentityEventsHandler
import io.github.slashncode.nothingeats.delivery.adapters.messaging.KafkaCourierPositions
import io.github.slashncode.nothingeats.delivery.adapters.messaging.OrderEventsHandler
import io.github.slashncode.nothingeats.delivery.adapters.messaging.OutboxDeliveryEvents
import io.github.slashncode.nothingeats.delivery.adapters.persistence.ExposedDeliveries
import io.github.slashncode.nothingeats.delivery.adapters.persistence.ExposedTransactions
import io.github.slashncode.nothingeats.delivery.adapters.simulation.SimulationTicker
import io.github.slashncode.nothingeats.delivery.application.AdvanceDeliveries
import io.github.slashncode.nothingeats.delivery.application.CourierPositions
import io.github.slashncode.nothingeats.delivery.application.Deliveries
import io.github.slashncode.nothingeats.delivery.application.DeliveryEvents
import io.github.slashncode.nothingeats.delivery.application.ForgetUser
import io.github.slashncode.nothingeats.delivery.application.PublishCourierPositions
import io.github.slashncode.nothingeats.delivery.application.QuoteDelivery
import io.github.slashncode.nothingeats.delivery.application.StartDelivery
import io.github.slashncode.nothingeats.delivery.application.TrackDelivery
import io.github.slashncode.nothingeats.delivery.application.Transactions
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.grpc.AuthInterceptor
import io.github.slashncode.nothingeats.platform.grpc.ErrorInterceptor
import io.github.slashncode.nothingeats.platform.grpc.GrpcServer
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.EventConsumer
import io.github.slashncode.nothingeats.platform.kafka.EventConsumerConfig
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.kafka.OutboxRelay
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import io.grpc.BindableService
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes, gRPC services, event handlers and cron tasks are
 * collected with `@IntoSet`. [jwtAuth] is passed in so tests can use test signing keys.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
    @get:Provides val jwtAuth: JwtAuth,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val deliveries: Deliveries

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        auth: JwtAuth,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, auth, extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun grpcServer(
        services: Set<BindableService>,
        auth: JwtAuth,
    ): GrpcServer = GrpcServer(config.grpc, services, AuthInterceptor(auth), ErrorInterceptor())

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @IntoSet
    @Provides
    fun grpcLifecycle(server: GrpcServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Messaging

    @ServiceScope
    @Provides
    fun outbox(clock: Clock): Outbox = Outbox(source = DeliveryTopics.SOURCE, clock = clock)

    @ServiceScope
    @Provides
    fun outboxRelay(postgres: PostgresDatabase): OutboxRelay =
        OutboxRelay(postgres, KafkaClients.producer(config.kafka, "delivery-service"))

    @IntoSet
    @Provides
    fun outboxRelayLifecycle(relay: OutboxRelay): LifecycleComponent = relay

    @ServiceScope
    @Provides
    fun eventConsumer(
        handlers: Set<EventHandler>,
        postgres: PostgresDatabase,
    ): EventConsumer =
        EventConsumer(
            config = EventConsumerConfig(groupId = "delivery-service"),
            handlers = handlers,
            db = postgres,
            consumerFactory = { KafkaClients.consumer(config.kafka, "delivery-service") },
            deadLetters = KafkaClients.producer(config.kafka, "delivery-service-dlq"),
        )

    @IntoSet
    @Provides
    fun eventConsumerLifecycle(consumer: EventConsumer): LifecycleComponent = consumer

    @IntoSet
    @Provides
    fun eventConsumerHealth(consumer: EventConsumer): HealthCheck = consumer

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Delivery

    @ServiceScope
    @Provides
    fun deliveries(postgres: PostgresDatabase): Deliveries = ExposedDeliveries(postgres)

    @Provides
    fun transactions(postgres: PostgresDatabase): Transactions = ExposedTransactions(postgres)

    @Provides
    fun deliveryEvents(outbox: Outbox): DeliveryEvents = OutboxDeliveryEvents(outbox)

    @ServiceScope
    @Provides
    fun courierPositions(clock: Clock): CourierPositions =
        KafkaCourierPositions(KafkaClients.producer(config.kafka, "delivery-service-couriers"), clock)

    @Provides
    fun startDelivery(
        deliveries: Deliveries,
        events: DeliveryEvents,
        transactions: Transactions,
        clock: Clock,
    ): StartDelivery = StartDelivery(deliveries, events, transactions, config.simulation, clock)

    @ServiceScope
    @Provides
    fun ticker(
        deliveries: Deliveries,
        events: DeliveryEvents,
        positions: CourierPositions,
        analytics: ProductAnalytics,
        clock: Clock,
    ): SimulationTicker =
        SimulationTicker(
            AdvanceDeliveries(deliveries, events, analytics, clock),
            PublishCourierPositions(deliveries, positions, config.ticker.positionInterval, clock),
            config.ticker.tick,
            clock,
        )

    @IntoSet
    @Provides
    fun tickerLifecycle(ticker: SimulationTicker): LifecycleComponent = ticker

    @IntoSet
    @Provides
    fun tickerHealth(ticker: SimulationTicker): HealthCheck = ticker

    @IntoSet
    @Provides
    fun orderEvents(start: StartDelivery): EventHandler = OrderEventsHandler(start)

    @IntoSet
    @Provides
    fun identityEvents(deliveries: Deliveries): EventHandler = IdentityEventsHandler(ForgetUser(deliveries))

    @IntoSet
    @Provides
    fun deliveryGrpc(deliveries: Deliveries): BindableService = DeliveryGrpcService(QuoteDelivery(deliveries, config.simulation))

    @IntoSet
    @Provides
    fun trackingRoutes(
        deliveries: Deliveries,
        clock: Clock,
    ): HttpRoutes = TrackingRoutes(TrackDelivery(deliveries, clock))
}
