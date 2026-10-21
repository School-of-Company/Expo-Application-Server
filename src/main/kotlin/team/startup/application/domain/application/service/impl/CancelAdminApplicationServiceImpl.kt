package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminCancellationResult
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.CancelAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.CancelStudentGuardianApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.repository.OwnerCancellationDeliveryRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.CancelAdminApplicationService
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.CancelStudentGuardianApplicationService

@Service
class CancelAdminApplicationServiceImpl(
    private val expo: AdminExpoGateway,
    private val applications: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val deliveries: OwnerCancellationDeliveryRepository,
    private val recovery: ProductionOwnerCancellationGuard,
    private val cancel: CancelProgramSessionService,
    private val transactions: TransactionTemplate,
    private val guardianBatches: StudentGuardianBatchRepository,
    private val guardianCancellation: CancelStudentGuardianApplicationService,
) : CancelAdminApplicationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelAdminApplicationCommand): AdminCancellationResult {
        val access = expo.requireCancelAccess(command.expoId, command.authorization)
        val original = target(command)
        if (guardianBatches.batchId(original.id) != null) {
            guardianCancellation.execute(
                CancelStudentGuardianApplicationCommand(command.expoId, original.id, authorization = command.authorization),
            )
            return AdminCancellationResult(command.applicationId)
        }
        val type = OwnerApplicationType.valueOf(original.key.programType.name)
        val deliveryCommand = CancelOwnerApplicationCommand(command.expoId, "", type, command.applicationId.toString())
        recovery.recover(deliveryCommand)
        deliveries.lock(deliveryCommand).use {
            if (deliveries.find(deliveryCommand) != null) conflict("이전 취소 보호 복구가 진행 중입니다.")
            if (target(command).status == "CANCELLED") return AdminCancellationResult(command.applicationId)
            // #261: 그 프로그램에 출석하지 않았으면 입장 여부와 취소 마감에 관계없이 취소한다. 좌석은 신청 경로의 풀로 돌아간다.
            recovery.acquireAdminProgram(deliveryCommand, original.participantId, original.key.programId).use { permit ->
                if (type == OwnerApplicationType.TRAINING && original.status == "CONFIRMED" &&
                    (original.traineeId == null || original.traineeId != permit.linkedTraineeId)
                ) {
                    conflict("연수자 연결이 변경되었습니다.")
                }
                transactions.executeWithoutResult {
                    ledger.lockParticipant(original.participantId)
                    permit.linkedTraineeId?.let(trainings::lockTrainee)
                    val current = target(command)
                    if (current.participantId != original.participantId || current.key != original.key ||
                        current.traineeId != original.traineeId
                    ) {
                        conflict("취소 대상 신청이 변경되었습니다.")
                    }
                    cancel.execute(CancelProgramSessionCommand(command.applicationId, ApplicationActorType.ADMIN, actorId = access.adminId))
                }
            }
        }
        return AdminCancellationResult(command.applicationId)
    }

    private fun target(command: CancelAdminApplicationCommand): ProgramWaitlistRepository.Entry =
        applications.entry(command.applicationId)?.takeIf { it.key.expoId == command.expoId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun conflict(reason: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, reason)
}
