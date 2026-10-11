package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import java.time.Instant

/**
 * No production adapter exists. A future provider must reject entered/attended participants and
 * hold ownership, QR entry and Attendance changes against cancellation until the permit closes
 * after the local commit. A boolean attendance lookup cannot implement this contract safely.
 */
interface OwnerCancellationGuard {
    fun acquire(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        party: OwnerApplicationParty,
    ): Permit

    interface Permit : AutoCloseable {
        val linkedTraineeId: Long?

        // Authoritative, separate cancellation deadline; never substitute the registration/session end.
        val cancelEndedAt: Instant

        override fun close()
    }
}
