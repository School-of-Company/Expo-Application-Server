package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.FieldVisitRequestRepository

@Component
@ConditionalOnProperty(name = ["application.field-visits.enabled"], havingValue = "true")
class FieldVisitRecoveryScheduler(
    private val requests: FieldVisitRequestRepository,
    private val workflow: RegisterFieldVisitServiceImpl,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${application.field-visits.recovery-delay-ms:60000}",
        initialDelayString = "\${application.field-visits.recovery-delay-ms:60000}",
    )
    fun recover() {
        requests.due().forEach { (expoId, requestId, ownerHash) ->
            try {
                workflow.recover(expoId, requestId, ownerHash)
            } catch (ex: Exception) {
                requests.retry(expoId, requestId)
                log.warn("Field visit recovery failed with {}", ex.javaClass.simpleName)
            }
        }
    }
}
