package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.service.ReconcileProgramSessionService
import java.time.Clock

@Component
@EnableScheduling
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class ProgramWaitlistScheduler(
    private val repository: ProgramWaitlistRepository,
    private val reconcile: ReconcileProgramSessionService,
    private val waitlist: ProgramWaitlist,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${application.program-sessions.reconcile-delay:30000}",
        initialDelayString = "\${application.program-sessions.reconcile-delay:30000}",
    )
    fun reconcile() {
        for (key in repository.keys(clock.instant())) {
            try {
                val current = repository.settledDefinition(key)
                val policy = current?.policy
                if (current != null && policy != null &&
                    (
                        repository.waitingCount(current) == 0 ||
                            (policy.channels ?: listOf(null)).none { waitlist.free(current, it?.name) > 0 }
                    )
                ) {
                    continue
                }
                reconcile.execute(key)
            } catch (exception: RuntimeException) {
                log.warn("프로그램 회차 복구 보류: {} / {} / {} ({})", key.expoId, key.programType, key.programId, exception.javaClass.simpleName)
            }
        }
    }
}
