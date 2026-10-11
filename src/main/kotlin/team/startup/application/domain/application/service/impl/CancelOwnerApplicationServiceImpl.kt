package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Clock
import java.util.UUID

@Service
class CancelOwnerApplicationServiceImpl(
    private val gateway: RegistrationGateway,
    private val owners: OwnerApplicationRepository,
    private val preregister: PreregisterSessionRepository,
    private val programs: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val ledger: PreregisterSessionLedger,
    private val attendance: SessionAttendanceOutbox,
    private val cancelProgram: CancelProgramSessionService,
    private val guards: ObjectProvider<OwnerCancellationGuard>,
    private val clock: Clock,
) : CancelOwnerApplicationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelOwnerApplicationCommand) {
        val party = gateway.managedParty(command.expoId, command.sessionId)
        val original =
            owners.cancellationTarget(command, party) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (original.status == "CANCELLED") return
        val guard =
            guards.ifAvailable ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "소유권·입장·출석·취소 기한 보호 계약이 준비되지 않았습니다.")
        guard.acquire(command, original.participantId, party).use { permit ->
            ledger.transactions.executeWithoutResult {
                ledger.lock(command.expoId)
                programs.lockParticipant(original.participantId)
                permit.linkedTraineeId?.let(trainings::lockTrainee)
                val current =
                    owners.cancellationTarget(command, party) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                if (current.status == "CANCELLED") return@executeWithoutResult
                if (!clock.instant().isBefore(permit.cancelEndedAt)) conflict("신청 취소 기간이 끝났습니다.")
                if (command.type == OwnerApplicationType.PREREGISTER) {
                    ledger.ensureAvailable(command.expoId, current.sessionOrProgramId)
                    if (owners.activePrograms(command.expoId, current.participantId, permit.linkedTraineeId)) {
                        conflict("활성 프로그램 신청을 먼저 취소해야 합니다.")
                    }
                    if (current.participantId == party.representativeId && owners.activeCompanions(command.expoId, current.participantId)) {
                        conflict("동행자의 사전등록을 먼저 취소해야 합니다.")
                    }
                    preregister.status(current.applicationId.toLong(), "CANCELLED")
                    attendance.cancelled(command.expoId, current.participantId)
                } else {
                    cancelProgram.execute(CancelProgramSessionCommand(UUID.fromString(current.applicationId)))
                }
            }
        }
    }
}
