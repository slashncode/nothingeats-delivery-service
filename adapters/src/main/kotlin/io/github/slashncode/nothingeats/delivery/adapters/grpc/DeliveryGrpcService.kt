package io.github.slashncode.nothingeats.delivery.adapters.grpc

import io.github.slashncode.nothingeats.delivery.application.Location
import io.github.slashncode.nothingeats.delivery.application.QuoteDelivery
import io.github.slashncode.nothingeats.delivery.grpc.v1.DeliveryServiceGrpcKt
import io.github.slashncode.nothingeats.delivery.grpc.v1.GeoPoint
import io.github.slashncode.nothingeats.delivery.grpc.v1.QuoteDeliveryRequest
import io.github.slashncode.nothingeats.delivery.grpc.v1.QuoteDeliveryResponse
import io.github.slashncode.nothingeats.delivery.grpc.v1.quoteDeliveryResponse

/** `nothingeats.delivery.v1.DeliveryService`; auth and error mapping come from platform-grpc. */
class DeliveryGrpcService(
    private val quote: QuoteDelivery,
) : DeliveryServiceGrpcKt.DeliveryServiceCoroutineImplBase() {
    override suspend fun quoteDelivery(request: QuoteDeliveryRequest): QuoteDeliveryResponse {
        val result = quote(request.pickup.location(), request.dropOff.location(), request.preparationMinutes)
        return quoteDeliveryResponse {
            etaSeconds = result.realDuration.inWholeSeconds.toInt()
            simulatedMinutes = result.simulatedMinutes
            distanceMeters = result.distanceMeters
            courierShortage = result.courierShortage
        }
    }

    private fun GeoPoint.location() = Location(latitude, longitude)
}
