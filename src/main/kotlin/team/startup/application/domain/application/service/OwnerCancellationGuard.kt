package team.startup.application.domain.application.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
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

    fun acquireAdminProgram(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        programId: Long,
    ): Permit = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리자 프로그램 취소 보호 공급자가 준비되지 않았습니다.")

    interface Permit : AutoCloseable {
        val linkedTraineeId: Long?

        // Authoritative, separate cancellation deadline; never substitute the registration/session end.
        val cancelEndedAt: Instant

        // Called inside the local transaction; remote delivery happens only after it completes.
        fun committed(cancellationRevision: Long? = null) {}

        override fun close()
    }
}
