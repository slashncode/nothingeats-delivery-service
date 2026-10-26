package io.github.slashncode.nothingeats.delivery.adapters.simulation

import io.github.slashncode.nothingeats.delivery.application.AdvanceDeliveries
import io.github.slashncode.nothingeats.delivery.application.PublishCourierPositions
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The simulation's heartbeat on every pod: advance due deliveries, publish courier positions.
 * The state lives in Postgres (planned times, next_step_at), so a pod that dies loses nothing:
 * another one, or the same after a restart, picks the due rows up on its next tick.
 */
class SimulationTicker(
    private val advance: AdvanceDeliveries,
    private val positions: PublishCourierPositions,
    private val tick: Duration = 1.seconds,
    private val clock: Clock = Clock.systemUTC(),
) : LifecycleComponent,
    HealthCheck {
    override val name = "simulation"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(SimulationTicker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var lastTick: Instant? = null

    override suspend fun onReady() {
        job =
            scope.launch {
                while (isActive) {
                    try {
                        advance()
                        positions()
                        lastTick = clock.instant()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A database hiccup skips a tick; the due rows are still due on the next one.
                        log.warn("Simulation tick failed", e)
                    }
                    delay(tick)
                }
            }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
    }

    /** Down when ticks stopped succeeding for a while (not before the first tick). */
    override suspend fun check(): HealthStatus {
        val last = lastTick ?: return HealthStatus.UP
        val stale = clock.instant().minusMillis((tick * STALE_TICKS).inWholeMilliseconds)
        return if (last.isAfter(stale)) HealthStatus.UP else HealthStatus.down("no successful tick since $last")
    }

    private companion object {
        const val STALE_TICKS = 30
    }
}
