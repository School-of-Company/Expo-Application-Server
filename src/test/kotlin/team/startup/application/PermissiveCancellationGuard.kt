package team.startup.application

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Instant

/** For ledger tests that use internal cancellation as setup; guard behavior is covered by the owner/HTTP tests. */
@TestConfiguration
class PermissiveCancellationGuard {
    @Bean @Primary
    fun permissiveOwnerCancellationGuard(): OwnerCancellationGuard =
        object : OwnerCancellationGuard {
            override fun acquire(
                command: CancelOwnerApplicationCommand,
                participantId: Long,
                party: OwnerApplicationParty,
            ) = permit()

            override fun acquireAdmin(
                command: CancelOwnerApplicationCommand,
                participantId: Long,
                sessionId: Long,
            ) = permit()

            private fun permit() =
                object : OwnerCancellationGuard.Permit {
                    override val linkedTraineeId: Long? = null
                    override val cancelEndedAt: Instant = Instant.MAX

                    override fun close() {}
                }
        }
}
