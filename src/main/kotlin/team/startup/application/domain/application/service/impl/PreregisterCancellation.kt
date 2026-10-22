package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Component
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Clock
import java.util.UUID

/** Protection rules shared by owner, admin and internal preregister cancellation. Callers hold the Expo ledger lock in a transaction. */
@Component
class PreregisterCancellation(
    private val owners: OwnerApplicationRepository,
    private val preregister: PreregisterSessionRepository,
    private val programs: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val ledger: PreregisterSessionLedger,
    private val attendance: SessionAttendanceOutbox,
    private val clock: Clock,
    private val reservations: PreregisterReservationRepository,
    private val notifications: ApplicationNotifications,
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
        // The authenticated representative for owner cancellation; null (UNKNOWN) for unauthenticated internal callers.
        actorParticipantId: Long?,
        // The verified admin for admin cancellation (#182); never passed together with actorParticipantId.
        adminId: Long? = null,
    ) {
        val actorType =
            when {
                adminId != null -> ApplicationActorType.ADMIN
                actorParticipantId != null -> ApplicationActorType.USER
                else -> ApplicationActorType.UNKNOWN
            }
        if (!clock.instant().isBefore(permit.cancelEndedAt)) conflict("신청 취소 기간이 끝났습니다.")
        ledger.ensureAvailable(expoId, sessionId)
        if (owners.activePrograms(expoId, participantId, permit.linkedTraineeId)) {
            conflict("활성 프로그램 신청을 먼저 취소해야 합니다.")
        }
        preregister.status(applicationId, "CANCELLED", actorType, actorParticipantId, adminId)
        notifications.preregisterCancelled(expoId, applicationId, sessionId, participantId, actorType)
        // 이 회차의 대표자 신청에 붙이려고 기다리던 동행자 추가(#169)는 붙일 곳이 없어지므로 함께 끝낸다.
        // #198: 대표자 취소는 회차별이고 여기서는 남은 동행자를 막지 않는다. 본인 취소만 #257로 CancelOwnerApplicationServiceImpl이 막는다.
        if (participantId == representativeId) {
            reservations.withdrawCompanionWaits(expoId, sessionId, participantId, actorType, adminId)
        }
        val outboxId = attendance.cancelled(expoId, participantId, sessionId)
        permit.committed(Math.addExact(Math.multiplyExact(outboxId, 2L), 1L), sessionId)
    }
}
