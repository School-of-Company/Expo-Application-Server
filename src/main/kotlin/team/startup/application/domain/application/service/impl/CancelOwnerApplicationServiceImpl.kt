package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.CancelStudentGuardianApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.CancelStudentGuardianApplicationService
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Clock
import java.util.UUID

@Service
class CancelOwnerApplicationServiceImpl(
    private val gateway: RegistrationGateway,
    private val owners: OwnerApplicationRepository,
    private val preregisterCancellation: PreregisterCancellation,
    private val ledger: PreregisterSessionLedger,
    private val cancelProgram: CancelProgramSessionService,
    private val guards: ObjectProvider<OwnerCancellationGuard>,
    private val clock: Clock,
    private val waitlist: PreregisterWaitlist,
    private val guardianBatches: StudentGuardianBatchRepository,
    private val guardianCancellation: CancelStudentGuardianApplicationService,
    private val fieldCancellation: FieldVisitCancellation,
) : CancelOwnerApplicationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelOwnerApplicationCommand) {
        val party = gateway.managedParty(command.expoId, command.sessionId)
        if (command.type == OwnerApplicationType.FIELD) {
            fieldCancellation.execute(command, party)
            return
        }
        val original =
            owners.cancellationTarget(command, party) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (command.type != OwnerApplicationType.PREREGISTER && guardianBatches.batchId(UUID.fromString(original.applicationId)) != null) {
            guardianCancellation.execute(
                CancelStudentGuardianApplicationCommand(
                    command.expoId,
                    UUID.fromString(original.applicationId),
                    sessionId = command.sessionId,
                ),
            )
            return
        }
        val guard =
            guards.ifAvailable ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "소유권·입장·출석·취소 기한 보호 계약이 준비되지 않았습니다.")
        guard.recover(command)
        if (original.status == "CANCELLED") return
        guard.acquire(command, original.participantId, party).use { permit ->
            ledger.transactions.executeWithoutResult {
                ledger.lock(command.expoId)
                preregisterCancellation.lockParticipant(original.participantId, permit)
                val current =
                    owners.cancellationTarget(command, party) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                if (current.status == "CANCELLED") return@executeWithoutResult
                if (command.type == OwnerApplicationType.TRAINING && current.status == "CONFIRMED" &&
                    (current.traineeId == null || current.traineeId != permit.linkedTraineeId)
                ) {
                    conflict("연결된 연수자 정보가 변경되었습니다. 취소를 재시도해 주세요.")
                }
                if (!clock.instant().isBefore(permit.cancelEndedAt)) conflict("신청 취소 기간이 끝났습니다.")
                if (command.type == OwnerApplicationType.PREREGISTER) {
                    // #257: 본인 취소만 막는다. 관리자·회차 일괄 취소는 일행 전체를 다루므로 PreregisterCancellation에 두지 않는다
                    if (current.participantId == party.representativeId &&
                        owners.activeCompanions(command.expoId, current.sessionOrProgramId, current.participantId)
                    ) {
                        throw OwnerCompanionsActiveException()
                    }
                    preregisterCancellation.cancel(
                        command.expoId,
                        current.sessionOrProgramId,
                        current.applicationId.toLong(),
                        current.participantId,
                        party.representativeId,
                        permit,
                        requireNotNull(party.representativeId),
                    )
                } else {
                    cancelProgram.execute(
                        CancelProgramSessionCommand(
                            UUID.fromString(current.applicationId),
                            ApplicationActorType.USER,
                            actorParticipantId = requireNotNull(party.representativeId),
                        ),
                    )
                    permit.committed()
                }
            }
        }
        if (command.type == OwnerApplicationType.PREREGISTER) waitlist.promoteQuietly(command.expoId, original.sessionOrProgramId)
    }
}

class OwnerCompanionsActiveException : ResponseStatusException(HttpStatus.CONFLICT, "동행자의 사전등록을 먼저 취소해야 합니다.")
