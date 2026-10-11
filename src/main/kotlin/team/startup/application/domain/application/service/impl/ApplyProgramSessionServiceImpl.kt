package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ApplyProgramSessionService
import team.startup.application.domain.application.service.ProgramFormProvider
import java.util.UUID

@Service
class ApplyProgramSessionServiceImpl(
    private val ledger: ProgramSessionApplicationRepository,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val gateway: RegistrationGateway,
    private val forms: ObjectProvider<ProgramFormProvider>,
    private val waitlist: ProgramWaitlist,
    private val repository: ProgramWaitlistRepository,
) : ApplyProgramSessionService {
    @Transactional
    override fun execute(request: ApplyProgramSessionCommand): ProgramSessionReceipt {
        val expoId =
            try {
                UUID.fromString(request.expoId).toString().also { require(it.equals(request.expoId, ignoreCase = true)) }
            } catch (ex: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "박람회 ID는 UUID여야 합니다.", ex)
            }
        val command = request.copy(expoId = expoId)
        require(command.expoId.isNotBlank() && command.programId > 0 && command.participantId > 0 && command.formVersion > 0)
        require(command.sessionId.isNotBlank() && command.idempotencyKey.isNotBlank() && command.idempotencyKey.length <= 200)
        require(command.answers.isObject)
        val occupation = gateway.managedOccupation(command.expoId, command.sessionId, command.participantId)
        ledger.lockParticipant(command.participantId)
        ledger.replay(command)?.let { (sameCommand, receipt) ->
            if (!sameCommand) conflict("같은 멱등 키에 다른 신청 내용이 있습니다.")
            return receipt
        }
        val definition =
            gateway.programDefinition(command.expoId, command.programType, command.programId)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정책 공급자가 준비되지 않았습니다.")
        val policy = definition.policy ?: conflict("참가자 신청 정책이 없는 프로그램입니다.")
        if (!ledger.confirmedPreregister(command)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "확정된 사전등록 참가자만 신청할 수 있습니다.")
        }
        if (occupation == null || occupation !in policy.allowedOccupations) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "신청 가능한 참가자 역할이 아닙니다.")
        }
        val provider =
            forms.ifAvailable
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 폼 조회 계약이 준비되지 않았습니다.")
        val form = provider.loadAndValidate(command.expoId, policy.formId, command.formVersion, command.answers)
        if (form.expoId != command.expoId || form.id != policy.formId || form.purpose != "PROGRAM" || form.version != command.formVersion) {
            conflict("프로그램 폼 또는 버전이 변경되었습니다.")
        }
        val trainingId = if (command.programType == ProgramSessionType.TRAINING) form.trainingId(command.answers) else null
        waitlist.lock(definition)
        repository.observe(definition)
        val now = waitlist.now()
        if (policy.closed || policy.capacity <= 0 ||
            now.isBefore(
                policy.applicationStartedAt,
            ) || !now.isBefore(policy.applicationEndedAt) ||
            now.isBefore(form.startedAt) || !now.isBefore(form.endedAt)
        ) {
            conflict("프로그램 또는 폼 신청 기간이 아닙니다.")
        }
        if (waitlist.isDeleted(definition)) conflict("삭제된 프로그램입니다.")
        if (ledger.activeInGroup(command, definition.groupId)) conflict("같은 프로그램 묶음에 활성 신청이 있습니다.")
        if (command.programType == ProgramSessionType.STANDARD) {
            ensureNoLegacyGroup(
                command,
                definition.groupId,
                standards.findAllByParticipantId(command.participantId).map { it.standardProgramId },
                command.participantId,
            )
        }
        waitlist.promote(definition, now)
        val waitingCount = repository.waitingCount(definition)
        val waiting =
            ledger.confirmedCount(command.programType, command.programId) >= policy.capacity ||
                waitingCount > 0
        if (waiting && !now.isBefore(policy.promotionEndedAt)) conflict("프로그램 대기 접수 기간이 끝났습니다.")
        if (waiting && waitingCount >= policy.waitingCapacity) conflict("프로그램 대기 정원이 찼습니다.")
        val traineeId =
            if (command.programType == ProgramSessionType.TRAINING && !waiting) {
                val id = gateway.resolveTrainee(command.expoId, command.participantId, trainingId)
                // The ID is known only after acceptance; never wait behind a legacy replace holding the trainee lock.
                if (!trainings.tryLockTrainee(id)) conflict("연수 신청 변경 중입니다. 같은 멱등 키로 재시도해 주세요.")
                if (trainings.existsByTraineeIdAndTrainingProgramId(id, command.programId)) conflict("이미 신청한 연수 프로그램입니다.")
                ensureNoLegacyGroup(command, definition.groupId, trainings.findAllByTraineeId(id).map { it.trainingProgramId }, id)
                id
            } else if (command.programType == ProgramSessionType.STANDARD) {
                if (standards.existsByParticipantIdAndStandardProgramId(
                        command.participantId,
                        command.programId,
                    )
                ) {
                    conflict("이미 신청한 일반 프로그램입니다.")
                }
                null
            } else {
                null
            }
        val receipt =
            ProgramSessionReceipt(
                UUID.randomUUID(),
                command.programId,
                command.participantId,
                traineeId,
                if (waiting) "WAITING" else "CONFIRMED",
            )
        if (!waiting && traineeId == null) {
            standards.saveAndFlush(StandardProgramApplication(participantId = command.participantId, standardProgramId = command.programId))
        } else if (!waiting && traineeId != null) {
            operations.lockVersion(traineeId)
            trainings.saveAndFlush(TrainingProgramApplication(traineeId = traineeId, trainingProgramId = command.programId))
            operations.incrementVersion(traineeId)
        }
        ledger.save(command, definition, form, receipt)
        repository.event(receipt.applicationId, receipt.status)
        return receipt
    }

    private fun conflict(message: String): Nothing = throw ProgramApplicationConflictException(message)

    private fun ensureNoLegacyGroup(
        command: ApplyProgramSessionCommand,
        groupId: UUID,
        programIds: List<Long>,
        personId: Long,
    ) {
        val known = ledger.knownGroups(command.expoId, command.programType, personId)
        val definitions =
            gateway.programDefinitions(
                command.expoId,
                command.programType,
                programIds.filter { it !in known },
                existing = true,
            )
        if (definitions.any { it.groupId == groupId }) conflict("같은 프로그램 묶음에 기존 신청이 있습니다.")
    }
}
