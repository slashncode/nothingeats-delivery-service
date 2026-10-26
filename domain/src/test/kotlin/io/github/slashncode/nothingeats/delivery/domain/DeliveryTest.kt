package io.github.slashncode.nothingeats.delivery.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val rathaus = GeoPoint(53.5503, 9.9922)
private val sternschanze = GeoPoint(53.5636, 9.9654)
private val start = Instant.parse("2026-10-26T12:00:00Z")

private fun delivery() =
    Delivery(
        orderId = "o-1",
        userId = "u-1",
        restaurantName = "Phantom Pizza",
        pickup = rathaus,
        dropOff = sternschanze,
        dropOffLabel = "Zuhause",
        courier = Courier("Nora Nebel", Vehicle.BIKE),
        approach = Route.curved(rathaus.offset(1_000.0, 90.0), rathaus, bend = 0.2),
        route = Route.curved(rathaus, sternschanze, bend = 0.15),
        plan = DeliveryPlan.from(start, 3.minutes, preparationShare = 0.5),
        status = DeliveryStatus.ACCEPTED,
    )

class DeliveryTest :
    FunSpec({
        test("distances are great-circle meters, offsets go where they point") {
            rathaus.distanceTo(sternschanze) shouldBe (2_330.0 plusOrMinus 30.0)
            rathaus.distanceTo(rathaus.offset(1_000.0, 45.0)) shouldBe (1_000.0 plusOrMinus 1.0)
        }

        test("routes start and end where they should and curve in between") {
            val route = Route.curved(rathaus, sternschanze, bend = 0.15)

            route.pointAt(0.0) shouldBe rathaus
            route.pointAt(1.0) shouldBe sternschanze
            route.lengthMeters shouldBe (rathaus.distanceTo(sternschanze) * 1.05 plusOrMinus 150.0)
        }

        test("quotes add kitchen, ride and a wait when couriers are short") {
            val settings = SimulationSettings()
            val calm = Quotes.calculate(rathaus, sternschanze, preparationMinutes = 15, activeDeliveries = 0, settings)
            val busy = Quotes.calculate(rathaus, sternschanze, preparationMinutes = 15, activeDeliveries = 8, settings)

            calm.simulatedMinutes shouldBe 15 + 10 + 2
            calm.realDuration shouldBe (27 * 6).seconds
            calm.courierShortage shouldBe false
            busy.simulatedMinutes shouldBe calm.simulatedMinutes + 2 * 3
            busy.courierShortage shouldBe true
            shouldThrow<DeliveryOutOfRange> { Quotes.calculate(rathaus, GeoPoint(53.0, 10.0), 15, 0, settings) }
                .distanceMeters shouldBeGreaterThan 15_000
        }

        test("the simulation follows the plan, catching up on steps it missed") {
            val d = delivery()

            d.next shouldBe (DeliveryStatus.COOKING to d.plan.cookingAt)
            d.advance(start.plusSeconds(5)).second shouldBe emptyList()
            val (late, steps) = d.advance(d.plan.enRouteAt)
            steps shouldBe listOf(DeliveryStatus.COOKING, DeliveryStatus.PICKED_UP, DeliveryStatus.EN_ROUTE)
            late.status shouldBe DeliveryStatus.EN_ROUTE
            late.advance(d.plan.deliveredAt).first.next shouldBe null
        }

        test("the courier rides to the restaurant, waits, then rides to the customer") {
            val d = delivery()

            d.courierPosition(start) shouldBe d.approach.points.first()
            d.courierPosition(d.plan.pickedUpAt.plusMillis(1)) shouldBe rathaus
            d.courierPosition(d.plan.deliveredAt) shouldBe sternschanze
            val halfway = d.courierPosition(Instant.ofEpochMilli((d.plan.enRouteAt.toEpochMilli() + d.plan.deliveredAt.toEpochMilli()) / 2))
            halfway.distanceTo(rathaus) shouldBe (d.route.lengthMeters / 2 plusOrMinus 300.0)
        }

        test("nobody delivers to the middle of nowhere") {
            shouldThrow<InvalidDelivery> { GeoPoint(91.0, 0.0) }.field shouldBe "location"
        }
    })
