package team.startup.application.domain.application.service.impl

import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
class OwnerCancellationRecoveryScheduler(
    private val guard: ProductionOwnerCancellationGuard,
    private val fieldCancellation: FieldVisitCancellation,
) {
    @Scheduled(
        fixedDelayString = "\${application.owner.cancellation-recovery-delay-ms:60000}",
        initialDelayString = "\${application.owner.cancellation-recovery-delay-ms:60000}",
    )
    fun recover() {
        guard.recoverPending()
        fieldCancellation.recoverPending()
    }
}
