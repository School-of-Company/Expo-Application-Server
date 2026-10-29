package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@EnableScheduling
@ConditionalOnProperty(name = ["application.registration.enabled"], havingValue = "true")
class PreregisterRecoveryScheduler(
    private val reservations: PreregisterReservationRepository,
    private val workflow: PreregisterRegistrationWorkflow,
    private val gateway: RegistrationGateway,
    private val waitlist: PreregisterWaitlist,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${application.registration.recovery-delay-ms:1000}",
        initialDelayString = "\${application.registration.recovery-delay-ms:1000}",
    )
    fun recover() {
        reservations.due().forEach { (expoId, requestId) ->
            try {
                workflow.recover(expoId, requestId)
            } catch (ex: Exception) {
                log.warn("Preregistration recovery failed with {}", ex.javaClass.simpleName)
            }
        }
        deliverCounts { expoId, participantId -> gateway.countStandard(expoId.toString(), participantId) }
    }

    fun deliverCounts(
        nanoTime: () -> Long = System::nanoTime,
        send: (UUID, Long) -> Unit,
    ) {
        val started = nanoTime()
        val failedExpos = mutableSetOf<UUID>()
        val attempted = mutableSetOf<Pair<UUID, Long>>()
        var candidates = reservations.dueCounts().iterator()
        while (candidates.hasNext() && attempted.size < 100) {
            if (nanoTime() - started >= TimeUnit.SECONDS.toNanos(5)) return
            val candidate = candidates.next()
            if (!attempted.add(candidate)) continue
            val (expoId, participantId) = candidate
            val delivered =
                reservations.withCountDeliveryLock(expoId, participantId) { repository ->
                    if (!repository.countDue(expoId, participantId)) return@withCountDeliveryLock true
                    if (repository.expoDeleted(expoId)) {
                        repository.countDelivered(expoId, participantId)
                        return@withCountDeliveryLock true
                    }
                    try {
                        send(expoId, participantId)
                    } catch (ex: ResponseStatusException) {
                        repository.countRetry(expoId, participantId)
                        return@withCountDeliveryLock false
                    }
                    repository.countDelivered(expoId, participantId)
                    true
                }
            if (delivered == false) {
                failedExpos.add(expoId)
                candidates = reservations.dueCounts(failedExpos).iterator()
            }
        }
    }

    // Safety net for promotions deferred by remote failures or boundaries observed after a cancellation.
    @Scheduled(
        fixedDelayString = "\${application.registration.waitlist-promotion-delay-ms:30000}",
        initialDelayString = "\${application.registration.waitlist-promotion-delay-ms:30000}",
    )
    fun promote() {
        reservations.promotableSessions(waitlist.now()).forEach { (expoId, sessionId) -> waitlist.promoteQuietly(expoId, sessionId) }
    }
}
