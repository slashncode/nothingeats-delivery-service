package io.github.slashncode.nothingeats.delivery.adapters.messaging

import io.github.slashncode.nothingeats.delivery.application.ForgetUser
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
internal data class UserDeleted(
    val userId: String,
)

/** `user.deleted` (identity record 030): drop-off locations are personal data, so they go. */
class IdentityEventsHandler(
    private val forget: ForgetUser,
) : EventHandler {
    override val topic = "identity.user.v1"

    private val log = LoggerFactory.getLogger(IdentityEventsHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        if (event.type != DELETED) return
        val removed = forget(event.decode(UserDeleted.serializer()).userId)
        log.info("Account deleted: {} deliveries erased", removed)
    }

    private companion object {
        const val DELETED = "nothingeats.identity.user.deleted.v1"
    }
}
