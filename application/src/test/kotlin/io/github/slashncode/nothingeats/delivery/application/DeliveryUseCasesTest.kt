package io.github.slashncode.nothingeats.delivery.application

import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.SimulationSettings
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.random.Random

private val noon = Instant.parse("2026-10-26T12:00:00Z")

class DeliveryUseCasesTest :
    FunSpec({
        test("a placed order gets a courier at once, exactly once, on the promised schedule") {
            val deliveries = InMemoryDeliveries()
            val events = RecordingDeliveryEvents()
            val start = StartDelivery(deliveries, events, DirectTransactions, SimulationSettings(), MovableClock(noon), Random(7))

            start(order(quotedSeconds = 180))
            start(order(quotedSeconds = 180))

            val delivery = deliveries.rows.getValue("o-1")
            delivery.status shouldBe DeliveryStatus.ACCEPTED
            delivery.plan.deliveredAt shouldBe noon.plusSeconds(180)
            delivery.dropOffLabel shouldBe "Zuhause"
            delivery.approach.points.last() shouldBe delivery.pickup
            events.published shouldBe listOf("o-1" to DeliveryStatus.ACCEPTED)
        }

        test("the simulation advances due deliveries and counts the completed ones") {
            val deliveries = InMemoryDeliveries()
            val events = RecordingDeliveryEvents()
            val analytics = RecordingAnalytics()
            val clock = MovableClock(noon)
            StartDelivery(deliveries, events, DirectTransactions, SimulationSettings(), clock, Random(7))(order())
            val advance = AdvanceDeliveries(deliveries, events, analytics, clock)

            advance() shouldBe 0
            clock.now = noon.plusSeconds(181)
            advance() shouldBe 1

            deliveries.rows.getValue("o-1").status shouldBe DeliveryStatus.DELIVERED
            events.published.map { it.second } shouldBe DeliveryStatus.entries
            analytics.events.single().let {
                it.name shouldBe "delivery_completed"
                it.distinctId shouldBe "u-1"
            }
        }

        test("no server-side analytics for a customer who did not agree to it") {
            val deliveries = InMemoryDeliveries()
            val analytics = RecordingAnalytics()
            val clock = MovableClock(noon)
            StartDelivery(
                deliveries,
                RecordingDeliveryEvents(),
                DirectTransactions,
                SimulationSettings(),
                clock,
                Random(7),
            )(order(analytics = false))
            clock.now = noon.plusSeconds(181)

            AdvanceDeliveries(deliveries, RecordingDeliveryEvents(), analytics, clock)() shouldBe 1
            analytics.events shouldBe emptyList()
        }

        test("each active courier's position goes out once per interval") {
            val deliveries = InMemoryDeliveries()
            val positions = RecordingPositions()
            val clock = MovableClock(noon)
            StartDelivery(deliveries, RecordingDeliveryEvents(), DirectTransactions, SimulationSettings(), clock, Random(7))(order())
            val publish = PublishCourierPositions(deliveries, positions, clock = clock)

            publish() shouldBe 1
            publish() shouldBe 0
            clock.now = noon.plusSeconds(3)
            publish() shouldBe 1
            positions.published shouldHaveSize 2
            positions.published.last().userId shouldBe "u-1"
        }

        test("quotes are validated; tracking someone else's order looks like no order") {
            val deliveries = InMemoryDeliveries()
            StartDelivery(
                deliveries,
                RecordingDeliveryEvents(),
                DirectTransactions,
                SimulationSettings(),
                MovableClock(noon),
                Random(7),
            )(order())

            shouldThrow<AppError.Invalid> {
                QuoteDelivery(deliveries, SimulationSettings())(Location(53.55, 9.99), Location(52.52, 13.40), 15)
            }.code.value shouldBe "DELIVERY_OUT_OF_RANGE"
            shouldThrow<AppError.Invalid> {
                QuoteDelivery(deliveries, SimulationSettings())(Location(91.0, 9.99), Location(53.56, 9.96), 15)
            }.violations.single().field shouldBe "location"
            TrackDelivery(deliveries, MovableClock(noon))("o-1", "u-1").delivery.orderId shouldBe "o-1"
            shouldThrow<AppError.NotFound> { TrackDelivery(deliveries)("o-1", "u-2") }.code.value shouldBe "DELIVERY_NOT_FOUND"
        }

        test("a deleted user's deliveries are gone") {
            val deliveries = InMemoryDeliveries()
            val start =
                StartDelivery(
                    deliveries,
                    RecordingDeliveryEvents(),
                    DirectTransactions,
                    SimulationSettings(),
                    MovableClock(noon),
                    Random(7),
                )
            start(order("o-1", "u-1"))
            start(order("o-2", "u-2"))

            ForgetUser(deliveries)("u-1") shouldBe 1
            deliveries.rows.keys shouldBe setOf("o-2")
        }
    })
