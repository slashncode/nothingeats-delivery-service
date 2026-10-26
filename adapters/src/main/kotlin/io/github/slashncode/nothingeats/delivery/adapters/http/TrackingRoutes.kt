package io.github.slashncode.nothingeats.delivery.adapters.http

import io.github.slashncode.nothingeats.delivery.application.TrackDelivery
import io.github.slashncode.nothingeats.delivery.domain.DeliveryStatus
import io.github.slashncode.nothingeats.delivery.domain.GeoPoint
import io.github.slashncode.nothingeats.delivery.domain.Route
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.authenticated
import io.github.slashncode.nothingeats.platform.http.auth.user
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import io.ktor.server.routing.Route as KtorRoute

@Serializable
data class PointResponse(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class StepResponse(
    val status: String,
    val at: String,
    val done: Boolean,
)

@Serializable
data class CourierResponse(
    val name: String,
    val vehicle: String,
    val position: PointResponse,
)

@Serializable
data class DeliveryResponse(
    val orderId: String,
    val status: String,
    val restaurantName: String,
    val pickup: PointResponse,
    val dropOff: PointResponse,
    val dropOffLabel: String?,
    val courier: CourierResponse,
    /** The courier's ride to the restaurant, then to the customer; [lat, lng] pairs. */
    val approachRoute: List<PointResponse>,
    val deliveryRoute: List<PointResponse>,
    val steps: List<StepResponse>,
    val estimatedArrivalAt: String,
    val serverTime: String,
)

/**
 * `GET /api/delivery/v1/deliveries/{orderId}` for the signed-in customer: the plan, the routes and
 * where the courier is now. Live changes after that arrive over the notification stream.
 */
class TrackingRoutes(
    private val track: TrackDelivery,
) : HttpRoutes {
    override fun KtorRoute.register() {
        authenticated {
            get("/api/delivery/v1/deliveries/{orderId}") {
                val view = track(call.parameters["orderId"].orEmpty(), call.user.id)
                val d = view.delivery
                call.respond(
                    DeliveryResponse(
                        orderId = d.orderId,
                        status = d.status.name,
                        restaurantName = d.restaurantName,
                        pickup = d.pickup.response(),
                        dropOff = d.dropOff.response(),
                        dropOffLabel = d.dropOffLabel,
                        courier = CourierResponse(d.courier.name, d.courier.vehicle.name, view.courierAt.response()),
                        approachRoute = d.approach.response(),
                        deliveryRoute = d.route.response(),
                        steps =
                            DeliveryStatus.entries.map {
                                StepResponse(
                                    it.name,
                                    d.plan.at(it).toString(),
                                    it.ordinal <= d.status.ordinal,
                                )
                            },
                        estimatedArrivalAt = d.plan.deliveredAt.toString(),
                        serverTime = view.at.toString(),
                    ),
                )
            }
        }
    }

    private fun GeoPoint.response() = PointResponse(latitude, longitude)

    private fun Route.response() = points.map { it.response() }
}
