package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
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
import java.time.Clock
import java.util.UUID

@Service
class CancelAdminApplicationServiceImpl(
    private val expo: AdminExpoGateway,
    private val participants: AdminCancellationParticipantGateway,
    private val attendance: AttendanceCancellationAdapter,
    private val deadlines: OwnerCancellationDeadlineGateway,
    private val applications: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val deliveries: OwnerCancellationDeliveryRepository,
    private val recovery: ProductionOwnerCancellationGuard,
    private val cancel: CancelProgramSessionService,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
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
            if (!attendanceEnabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 취소 전달이 활성화되지 않았습니다.")
            val before = participants.status(command.expoId, original.participantId)
            if (before.entered) conflict("이미 입장한 참가자입니다.")
            if (type == OwnerApplicationType.TRAINING && original.status == "CONFIRMED" &&
                (original.traineeId == null || original.traineeId != before.linkedTraineeId)
            ) {
                conflict("연수자 연결이 변경되었습니다.")
            }
            val deadline = deadlines.program(command.expoId, type, original.key.programId)
            if (!clock.instant().isBefore(deadline)) conflict("신청 취소 기간이 끝났습니다.")
            val operationId = UUID.randomUUID()
            deliveries.insert(deliveryCommand, original.participantId, operationId)
            var failure: Exception? = null
            try {
                attendance.acquireAdmin(command.expoId, original.participantId, operationId)
                if (participants.status(command.expoId, original.participantId) != before) conflict("취소 보호 중 참가자 상태가 변경되었습니다.")
                transactions.executeWithoutResult {
                    ledger.lockParticipant(original.participantId)
                    before.linkedTraineeId?.let(trainings::lockTrainee)
                    val current = target(command)
                    if (current.participantId != original.participantId || current.key != original.key ||
                        current.traineeId != original.traineeId
                    ) {
                        conflict("취소 대상 신청이 변경되었습니다.")
                    }
                    if (!clock.instant().isBefore(deadline)) conflict("신청 취소 기간이 끝났습니다.")
                    cancel.execute(CancelProgramSessionCommand(command.applicationId, ApplicationActorType.ADMIN, actorId = access.adminId))
                    deliveries.abort(operationId)
                }
            } catch (exception: Exception) {
                failure = exception
                throw exception
            } finally {
                try {
                    deliveries.abort(operationId)
                    attendance.abort(command.expoId, original.participantId, operationId)
                    deliveries.delete(operationId)
                } catch (cleanup: Exception) {
                    deliveries.defer(operationId)
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
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
