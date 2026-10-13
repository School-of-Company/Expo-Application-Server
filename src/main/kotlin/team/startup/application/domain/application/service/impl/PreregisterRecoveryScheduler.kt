package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.PreregisterReservationRepository

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
        reservations.deliverCounts { expoId, participantId -> gateway.countStandard(expoId.toString(), participantId) }
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
