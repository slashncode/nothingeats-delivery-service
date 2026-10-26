package io.github.slashncode.nothingeats.delivery.adapters.persistence

import io.github.slashncode.nothingeats.delivery.application.DirectTransactions
import io.github.slashncode.nothingeats.delivery.application.MovableClock
import io.github.slashncode.nothingeats.delivery.application.RecordingDeliveryEvents
import io.github.slashncode.nothingeats.delivery.application.StartDelivery
import io.github.slashncode.nothingeats.delivery.application.order
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.SimulationSettings
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private val noon = Instant.parse("2026-10-26T12:00:00Z")

private suspend fun database(): PostgresDatabase {
    val config = PostgresTestDatabases.create()
    val migrations = FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE))
    migrations.migrate()
    return PostgresDatabase(config, migrations).also { it.start() }
}

class ExposedDeliveriesTest :
    FunSpec({
        test("a delivery survives the round trip, routes and plan included; inserting twice is a no-op") {
            val db = database()
            val deliveries = ExposedDeliveries(db)
            val start =
                StartDelivery(
                    deliveries,
                    RecordingDeliveryEvents(),
                    DirectTransactions,
                    SimulationSettings(),
                    MovableClock(noon),
                    Random(3),
                )

            start(order("o-1"))
            start(order("o-1"))

            val stored = deliveries.find("o-1")!!
            stored.status shouldBe DeliveryStatus.ACCEPTED
            stored.plan.deliveredAt shouldBe noon.plusSeconds(180)
            stored.route.points.size shouldBe 25
            deliveries.countActive() shouldBe 1
            db.stop()
        }

        test("two pods advancing at the same time never take the same delivery") {
            val db = database()
            val deliveries = ExposedDeliveries(db)
            val start =
                StartDelivery(
                    deliveries,
                    RecordingDeliveryEvents(),
                    DirectTransactions,
                    SimulationSettings(),
                    MovableClock(noon),
                    Random(3),
                )
            (1..20).forEach { start(order("o-$it")) }
            val handled = AtomicInteger()

            val counts =
                coroutineScope {
                    (1..2)
                        .map {
                            async {
                                deliveries.advanceDue(noon.plusSeconds(181), 50) { delivery ->
                                    handled.incrementAndGet()
                                    delay(50)
                                    delivery.advance(noon.plusSeconds(181)).first
                                }
                            }
                        }.awaitAll()
                }

            counts.sum() shouldBe 20
            handled.get() shouldBe 20
            deliveries.countActive() shouldBe 0
            deliveries.advanceDue(noon.plusSeconds(500), 50) { it } shouldBe 0
            db.stop()
        }

        test("positions are claimed once per interval; deleting a user removes their deliveries") {
            val db = database()
            val deliveries = ExposedDeliveries(db)
            val start =
                StartDelivery(
                    deliveries,
                    RecordingDeliveryEvents(),
                    DirectTransactions,
                    SimulationSettings(),
                    MovableClock(noon),
                    Random(3),
                )
            start(order("o-1", "u-1"))
            start(order("o-2", "u-2"))

            deliveries.claimPositions(noon, noon.minusSeconds(2), 10).size shouldBe 2
            deliveries.claimPositions(noon.plusSeconds(1), noon.minusSeconds(1), 10).size shouldBe 0
            deliveries.claimPositions(noon.plusSeconds(3), noon.plusSeconds(1), 10).size shouldBe 2

            deliveries.deleteByUser("u-1") shouldBe 1
            deliveries.find("o-1") shouldBe null
            deliveries.find("o-2")?.userId shouldBe "u-2"
            db.stop()
        }
    })
