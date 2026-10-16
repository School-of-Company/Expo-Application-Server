package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import java.time.Instant

interface OwnerCancellationGuard {
    fun recover(command: CancelOwnerApplicationCommand) {}

    fun acquire(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        party: OwnerApplicationParty,
    ): Permit

    // Internal (admin) preregister cancellation: no owner token, so User admin status and the Attendance admin guard replace phone checks.
    fun acquireAdmin(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        sessionId: Long,
    ): Permit

    interface Permit : AutoCloseable {
        val linkedTraineeId: Long?

        // Authoritative, separate cancellation deadline; never substitute the registration/session end.
        val cancelEndedAt: Instant

        // Called inside the local transaction; remote delivery happens only after it completes.
        fun committed(cancellationRevision: Long? = null) {}

        override fun close()
    }
}
