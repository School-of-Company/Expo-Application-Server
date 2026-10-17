package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import java.util.UUID

// 사전·현장 회차 신청의 공통 접수. 트랜잭션은 각 진입점 서비스가 연다.
@Component
class ProgramSessionAdmission(
    private val ledger: ProgramSessionApplicationRepository,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val gateway: RegistrationGateway,
    private val forms: ObjectProvider<ProgramFormProvider>,
    private val waitlist: ProgramWaitlist,
    private val repository: ProgramWaitlistRepository,
    private val guardianEligibility: ObjectProvider<StudentGuardianEligibilityProvider>,
) {
    fun admit(
        request: ApplyProgramSessionCommand,
        onsite: Boolean,
    ): ProgramSessionReceipt {
        val expoId =
            try {
                UUID.fromString(request.expoId).toString().also { require(it.equals(request.expoId, ignoreCase = true)) }
            } catch (ex: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "박람회 ID는 UUID여야 합니다.", ex)
            }
        val command = request.copy(expoId = expoId, onsite = onsite)
        require(command.expoId.isNotBlank() && command.programId > 0 && command.participantId > 0 && command.formVersion > 0)
        require(command.sessionId.isNotBlank() && command.idempotencyKey.isNotBlank() && command.idempotencyKey.length <= 200)
        require(command.answers.isObject)
        val managed = gateway.managedParticipant(command.expoId, command.sessionId, command.participantId)
        val occupation = managed.occupation
        ledger.lockParticipant(command.participantId)
        ledger.replay(command)?.let { (sameCommand, receipt) ->
            if (!sameCommand) conflict("같은 멱등 키에 다른 신청 내용이 있습니다.")
            return receipt
        }
        val definition =
            gateway.programDefinition(command.expoId, command.programType, command.programId)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정책 공급자가 준비되지 않았습니다.")
        val policy = definition.policy ?: conflict("참가자 신청 정책이 없는 프로그램입니다.")
        val guardianProvider = guardianEligibility.ifAvailable
        if (policy.guardianPolicy != null && guardianProvider == null) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "보호자 자격 공급자가 준비되지 않았습니다.")
        }
        guardianProvider?.validateSingle(command, definition, occupation)
        if (!ledger.confirmedPreregister(command) &&
            !(command.onsite && managed.fieldRegistered)
        ) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, ProgramIneligibleReason.PREREGISTRATION_NOT_CONFIRMED.name)
        }
        if (occupation == null || occupation !in policy.allowedOccupations) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, ProgramIneligibleReason.OCCUPATION_NOT_ALLOWED.name)
        }
        if (!policy.channelsValid() || !policy.admissionCapacitiesValid()) badGateway()
        if (command.onsite && !policy.hasAdmissionCapacities()) conflict("현장 접수 정원이 없는 프로그램입니다.")
        val channel = policy.channels?.let { policy.channelOf(occupation) ?: badGateway() }
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
        // 현장 접수는 사전 모집 기간이 아니라 회차의 현장 접수 기간에 연다. 폼 기간은 Expo가 두 기간을 모두 포함하도록 지킨다.
        val (opensAt, closesAt) = policy.admissionWindow(command.onsite) ?: conflict("현장 접수 기간이 설정되지 않은 프로그램입니다.")
        if (policy.closed || policy.capacity <= 0 || now.isBefore(opensAt) || !now.isBefore(closesAt) ||
            now.isBefore(form.startedAt) || !now.isBefore(form.endedAt)
        ) {
            conflict("프로그램 또는 폼 신청 기간이 아닙니다.")
        }
        if (waitlist.isDeleted(definition)) conflict("삭제된 프로그램입니다.")
        if (ledger.activeInGroup(command, definition.groupId)) {
            throw ProgramApplicationConflictException("같은 프로그램 묶음에 활성 신청이 있습니다.", ProgramIneligibleReason.GROUP_ALREADY_APPLIED)
        }
        if (ledger.hasTimeConflict(definition, command.participantId)) {
            throw ProgramApplicationConflictException("신청한 프로그램과 운영 시간이 겹칩니다.", ProgramIneligibleReason.TIME_CONFLICT)
        }
        if (command.programType == ProgramSessionType.STANDARD) {
            ensureNoLegacyGroup(
                command,
                definition.groupId,
                standards.findAllByParticipantId(command.participantId).map { it.standardProgramId },
                command.participantId,
            )
        }
        waitlist.promote(definition, now)
        // A channel never borrows another channel's vacancy; the session total still covers legacy rows without a channel.
        // Split sessions wait on pre-registration seats only; onsite seats are never borrowed.
        // Onsite admission confirms within onsite seats or is rejected; it never waits behind the pre-registration queue.
        if (command.onsite &&
            (ledger.occupiedCount(definition) >= policy.capacity || ledger.onsiteRemaining(definition) < 1)
        ) {
            conflict("현장 접수 좌석이 없습니다.")
        }
        val waitingCount = repository.waitingCount(definition, channel?.name)
        val waiting =
            !command.onsite &&
                (
                    ledger.occupiedCount(definition) >= policy.capacity ||
                        (policy.hasAdmissionCapacities() && ledger.preRegistrationRemaining(definition) < 1) ||
                        (channel != null && repository.confirmedCount(definition, channel.name) >= channel.capacity) ||
                        waitingCount > 0
                )
        if (waiting && waitingCount >= (channel?.waitingCapacity ?: policy.waitingCapacity)) conflict("프로그램 대기 정원이 찼습니다.")
        val traineeId =
            // Waiting entries get the same legacy-group check; a conflicting head would otherwise stall FIFO promotion (#116).
            if (command.programType == ProgramSessionType.TRAINING) {
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
        val acceptedAt = waitlist.now()
        if (!acceptedAt.isBefore(closesAt) || !acceptedAt.isBefore(form.endedAt)) {
            throw ProgramSessionAdmissionDeadlineException("프로그램 또는 폼 신청 기간이 아닙니다.")
        }
        if (waiting && !acceptedAt.isBefore(policy.promotionEndedAt)) {
            throw ProgramSessionAdmissionDeadlineException("프로그램 대기 접수 기간이 끝났습니다.")
        }
        val receipt =
            ProgramSessionReceipt(
                UUID.randomUUID(),
                command.programId,
                command.participantId,
                traineeId.takeUnless { waiting },
                if (waiting) "WAITING" else "CONFIRMED",
            )
        if (!waiting && traineeId == null) {
            standards.saveAndFlush(StandardProgramApplication(participantId = command.participantId, standardProgramId = command.programId))
        } else if (!waiting && traineeId != null) {
            operations.lockVersion(traineeId)
            trainings.saveAndFlush(TrainingProgramApplication(traineeId = traineeId, trainingProgramId = command.programId))
            operations.incrementVersion(traineeId)
        }
        ledger.save(command, definition, form, receipt, channel?.name)
        repository.event(receipt.applicationId, receipt.status)
        return receipt
    }

    private fun conflict(message: String): Nothing = throw ProgramApplicationConflictException(message)

    private fun badGateway(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 채널 정책을 확인할 수 없습니다.")

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
        if (definitions.any { it.groupId == groupId }) {
            throw ProgramApplicationConflictException("같은 프로그램 묶음에 기존 신청이 있습니다.", ProgramIneligibleReason.GROUP_ALREADY_APPLIED)
        }
    }
}

internal class ProgramSessionAdmissionDeadlineException(
    message: String,
) : ProgramApplicationConflictException(message)
