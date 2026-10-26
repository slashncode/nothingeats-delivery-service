package io.github.slashncode.nothingeats.delivery.app

import io.github.slashncode.nothingeats.delivery.grpc.v1.DeliveryServiceGrpcKt
import io.github.slashncode.nothingeats.delivery.grpc.v1.geoPoint
import io.github.slashncode.nothingeats.delivery.grpc.v1.quoteDeliveryRequest
import io.github.slashncode.nothingeats.platform.grpc.GrpcChannel
import io.github.slashncode.nothingeats.platform.grpc.GrpcClientConfig
import io.github.slashncode.nothingeats.platform.grpc.GrpcServerConfig
import io.github.slashncode.nothingeats.platform.grpc.errorCode
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun freePort() = ServerSocket(0).use { it.localPort }

/** Every event on [topic] so far. */
private fun eventsOn(topic: String): List<CloudEvent> =
    KafkaConsumer(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to KafkaTestCluster.bootstrapServers,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        ByteArrayDeserializer(),
    ).use { consumer ->
        val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        val end = consumer.endOffsets(partitions)
        val events = mutableListOf<CloudEvent>()
        while (partitions.any { consumer.position(it) < end.getValue(it) }) {
            consumer.poll(Duration.ofMillis(200)).forEach {
                events +=
                    EventJson.decodeFromString(CloudEvent.serializer(), it.value().decodeToString())
            }
        }
        events
    }

private fun orderPlaced(
    orderId: String,
    userId: String,
) = CloudEvent(
    id = UUID.randomUUID().toString(),
    source = "/nothingeats/ordering",
    type = "nothingeats.ordering.order.placed.v1",
    subject = orderId,
    time = "2026-10-26T12:00:00Z",
    data =
        buildJsonObject {
            put("orderId", orderId)
            put("userId", userId)
            putJsonObject("restaurant") {
                put("name", "Phantom Pizza")
                put("latitude", 53.5503)
                put("longitude", 9.9667)
                put("preparationMinutes", 15)
                put("somethingNew", "ignored")
            }
            putJsonObject("dropOff") {
                put("latitude", 53.5636)
                put("longitude", 9.9654)
                put("label", "Schanze")
            }
            put("quotedSeconds", 4)
        },
)

/** Orders in, simulated deliveries out: Kafka, the ticker, gRPC and HTTP through the real composition root. */
class DeliveryFlowTest :
    FunSpec({
        test("a placed order is delivered on schedule, quotable over gRPC and trackable over HTTP") {
            KafkaTestCluster.createTopics(
                "ordering.order.v1",
                "identity.user.v1",
                "delivery.delivery.v1",
                "delivery.courier.v1",
                "delivery-service.dlq",
            )
            val tokens = TestTokens()
            val httpPort = freePort()
            val grpcPort = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = httpPort),
                    grpc = GrpcServerConfig(port = grpcPort),
                    database = PostgresTestDatabases.create(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    ticker = TickerConfig(tick = 200.milliseconds, positionInterval = 500.milliseconds),
                    lifecycle = LifecycleConfig(drainDelay = kotlin.time.Duration.ZERO),
                )
            val service = ServiceComponent::class.create(config, tokens.jwtAuth)
            service.migrations.migrate()
            service.lifecycle.start()
            val channel = GrpcChannel(GrpcClientConfig(target = "localhost:$grpcPort"))
            try {
                val orderId = UUID.randomUUID().toString()
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    val event = orderPlaced(orderId, "ghost-1")
                    producer
                        .send(
                            ProducerRecord(
                                "ordering.order.v1",
                                orderId,
                                EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray(),
                            ),
                        ).get()
                    producer
                        .send(
                            ProducerRecord(
                                "ordering.order.v1",
                                orderId,
                                EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray(),
                            ),
                        ).get()
                }

                // Four real seconds from accepted to delivered, every step published once.
                eventually(20.seconds) {
                    val statuses =
                        eventsOn("delivery.delivery.v1")
                            .filter { it.subject == orderId }
                            .map {
                                it.data.jsonObject
                                    .getValue("status")
                                    .jsonPrimitive.content
                            }
                    statuses shouldBe listOf("ACCEPTED", "COOKING", "PICKED_UP", "EN_ROUTE", "DELIVERED")
                }
                eventsOn("delivery.courier.v1").count { it.subject == orderId } shouldBeGreaterThan 2

                val http = HttpClient.newHttpClient()

                fun track(token: String) =
                    http.send(
                        HttpRequest
                            .newBuilder(URI("http://localhost:$httpPort/api/delivery/v1/deliveries/$orderId"))
                            .header("Authorization", "Bearer $token")
                            .build(),
                        BodyHandlers.ofString(),
                    )
                track(tokens.accessToken(userId = "ghost-1")).let {
                    it.statusCode() shouldBe 200
                    it.body() shouldContain "\"status\":\"DELIVERED\""
                    it.body() shouldContain "\"dropOffLabel\":\"Schanze\""
                }
                track(tokens.accessToken(userId = "ghost-2")).statusCode() shouldBe 404

                val stub = DeliveryServiceGrpcKt.DeliveryServiceCoroutineStub(channel.channel)
                val near =
                    quoteDeliveryRequest {
                        pickup =
                            geoPoint {
                                latitude = 53.5503
                                longitude = 9.9667
                            }
                        dropOff =
                            geoPoint {
                                latitude = 53.5636
                                longitude = 9.9654
                            }
                        preparationMinutes = 15
                    }
                val quote = channel.prepare(stub, tokens.accessToken()).quoteDelivery(near)
                quote.etaSeconds shouldBeGreaterThan 0
                quote.simulatedMinutes shouldBeGreaterThan 15
                val far =
                    near
                        .toBuilder()
                        .setDropOff(
                            geoPoint {
                                latitude = 52.52
                                longitude = 13.40
                            },
                        ).build()
                shouldThrow<StatusException> { channel.prepare(stub, tokens.accessToken()).quoteDelivery(far) }.let {
                    it.status.code shouldBe Status.Code.INVALID_ARGUMENT
                    it.errorCode() shouldBe "DELIVERY_OUT_OF_RANGE"
                }
                shouldThrow<StatusException> { channel.prepare(stub).quoteDelivery(near) }.status.code shouldBe Status.Code.UNAUTHENTICATED

                // GDPR: the account goes, its drop-off locations with it.
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    val deleted =
                        CloudEvent(
                            id = UUID.randomUUID().toString(),
                            source = "/nothingeats/identity",
                            type = "nothingeats.identity.user.deleted.v1",
                            subject = "ghost-1",
                            time = "2026-10-26T12:00:00Z",
                            data = buildJsonObject { put("userId", "ghost-1") },
                        )
                    producer
                        .send(
                            ProducerRecord(
                                "identity.user.v1",
                                "ghost-1",
                                EventJson.encodeToString(CloudEvent.serializer(), deleted).toByteArray(),
                            ),
                        ).get()
                }
                eventually(20.seconds) { service.deliveries.find(orderId) shouldBe null }
            } finally {
                channel.stop()
                service.lifecycle.stop()
            }
        }
    })
