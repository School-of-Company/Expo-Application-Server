package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Clock
import java.util.UUID

/** Protection rules shared by owner and internal preregister cancellation. Callers hold the Expo ledger lock in a transaction. */
@Component
class PreregisterCancellation(
    private val owners: OwnerApplicationRepository,
    private val preregister: PreregisterSessionRepository,
    private val programs: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val ledger: PreregisterSessionLedger,
    private val attendance: SessionAttendanceOutbox,
    private val clock: Clock,
) {
    fun lockParticipant(
        participantId: Long,
        permit: OwnerCancellationGuard.Permit,
    ) {
        programs.lockParticipant(participantId)
        permit.linkedTraineeId?.let(trainings::lockTrainee)
    }

    fun cancel(
        expoId: UUID,
        sessionId: Long,
        applicationId: Long,
        participantId: Long,
        representativeId: Long?,
        permit: OwnerCancellationGuard.Permit,
    ) {
        if (!clock.instant().isBefore(permit.cancelEndedAt)) conflict("신청 취소 기간이 끝났습니다.")
        ledger.ensureAvailable(expoId, sessionId)
        if (owners.activePrograms(expoId, participantId, permit.linkedTraineeId)) {
            conflict("활성 프로그램 신청을 먼저 취소해야 합니다.")
        }
        if (participantId == representativeId && owners.activeCompanions(expoId, participantId)) {
            conflict("동행자의 사전등록을 먼저 취소해야 합니다.")
        }
        preregister.status(applicationId, "CANCELLED")
        val outboxId = attendance.cancelled(expoId, participantId)
        permit.committed(Math.addExact(Math.multiplyExact(outboxId, 2L), 1L))
    }
}
