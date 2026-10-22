package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ApplyStudentGuardianBatchCommand
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchApplication
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchReceipt
import team.startup.application.domain.application.presentation.dto.StudentGuardianEligibilityQuery
import team.startup.application.domain.application.presentation.dto.StudentGuardianValidationPhase
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import java.util.UUID

// 사전·현장 학생·보호자 묶음 신청의 공통 접수. 트랜잭션은 각 진입점 서비스가 연다.
@Component
class StudentGuardianBatchAdmission(
    private val batches: StudentGuardianBatchRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val repository: ProgramWaitlistRepository,
    private val gateway: RegistrationGateway,
    private val forms: ObjectProvider<ProgramFormProvider>,
    private val eligibility: ObjectProvider<StudentGuardianEligibilityProvider>,
    private val waitlist: ProgramWaitlist,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val fieldEligibility: FieldVisitEligibility? = null,
) {
    fun admit(
        requested: ApplyStudentGuardianBatchCommand,
        onsite: Boolean,
    ): StudentGuardianBatchReceipt {
        val command = requested.copy(onsite = onsite)
        val request = command.request
        val ids = request.participants.map { it.participantId }
        val students = request.links.map { it.studentId }
        val guardians = request.links.map { it.guardianId }.toSet()
        if (command.key.programId <= 0 || command.sessionId.isBlank() ||
            command.idempotencyKey.isBlank() || command.idempotencyKey.length > 200 ||
            request.links.isEmpty() || ids.any { it <= 0 } || ids.toSet().size != ids.size || students.toSet().size != students.size ||
            students.any { it in guardians } || (students.toSet() + guardians) != ids.toSet()
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "참가자와 학생·보호자 연결이 올바르지 않습니다.")
        }
        val party = gateway.managedParty(command.key.expoId, command.sessionId)
        val owner = party.representativeId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        if (!party.participants.map { it.participantId }.containsAll(ids)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        batches.lockRequest(command.key.expoId, owner, command.idempotencyKey)
        ids.sorted().forEach(ledger::lockParticipant)
        batches.replay(command, owner)?.let { (same, receipt) ->
            if (!same) conflict("같은 멱등 키에 다른 일괄 신청이 있습니다.")
            return receipt
        }
        val provider = eligibility.ifAvailable ?: unavailable()
        val definition =
            gateway.programDefinition(command.key.expoId.toString(), command.key.programType, command.key.programId) ?: unavailable()
        val policy = definition.policy ?: conflict("참가자 신청 정책이 없습니다.")
        if (!policy.channelsValid() || !policy.admissionCapacitiesValid()) throw ResponseStatusException(HttpStatus.BAD_GATEWAY)
        if (command.onsite && !policy.hasAdmissionCapacities()) conflict("현장 접수 정원이 없는 프로그램입니다.")
        val occupations = ids.associateWith { id -> party.participants.single { it.participantId == id }.occupation }
        if (occupations.values.any { it == null || it !in policy.allowedOccupations }) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val channels = occupations.mapValues { (_, occupation) -> policy.channels?.let { policy.channelOf(requireNotNull(occupation)) } }
        if (channels.values
                .map { it?.name }
                .distinct()
                .size > 1
        ) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서로 다른 채널의 묶음 FIFO 계약이 필요합니다.")
        }
        val channel = channels.values.first()
        val batchId = UUID.randomUUID()
        ids
            .filterNot { id ->
                val current =
                    fieldEligibility?.current(
                        command.key.expoId,
                        id,
                        party.participants.single { it.participantId == id }.fieldRegistered,
                        command.onsite,
                    )
                        ?: party.participants.single { it.participantId == id }.fieldRegistered
                command.onsite && current
            }.firstNotNullOfOrNull { ledger.preregisterIneligibility(definition, it) }
            ?.let { throw ResponseStatusException(HttpStatus.FORBIDDEN, it.name) }
        val existing = guardians.mapNotNull { id -> batches.active(command.key, id)?.let { id to it } }.toMap()
        if (existing.values.any { it.status != "CONFIRMED" || batches.students(it.id).isEmpty() }) {
            conflict("연결 학생과 확정된 보호자만 재사용할 수 있습니다.")
        }
        provider.validate(
            StudentGuardianEligibilityQuery(
                definition,
                ids.toSet(),
                request.links,
                StudentGuardianValidationPhase.SUBMISSION,
                existing.keys,
                request.participants,
            ),
        )
        val newItems = request.participants.filter { it.participantId !in existing }
        if (request.participants.any { it.participantId in existing && (it.formVersion != null || it.answers != null) } ||
            newItems.any { it.formVersion == null || it.formVersion <= 0 || it.answers?.isObject != true }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "신규 참가자는 폼이 필요하고 재사용 보호자의 폼은 생략해야 합니다.")
        }
        val commands =
            newItems.associate { item ->
                item.participantId to
                    ApplyProgramSessionCommand(
                        command.key.expoId.toString(),
                        command.key.programType,
                        command.key.programId,
                        item.participantId,
                        requireNotNull(item.formVersion),
                        requireNotNull(item.answers),
                        command.sessionId,
                        "guardian-batch:$batchId",
                        command.onsite,
                    )
            }
        val formProvider = forms.ifAvailable ?: unavailable()
        val snapshots =
            newItems.associate { item ->
                val form =
                    formProvider.loadAndValidate(
                        definition.expoId,
                        policy.formId,
                        requireNotNull(item.formVersion),
                        requireNotNull(item.answers),
                    )
                if (form.expoId != definition.expoId || form.id != policy.formId ||
                    form.purpose != "PROGRAM" || form.version != item.formVersion
                ) {
                    conflict("프로그램 폼 또는 버전이 변경되었습니다.")
                }
                item.participantId to form
            }
        waitlist.lock(definition)
        repository.observe(definition)
        if (existing.any { (id, entry) -> batches.active(command.key, id) != entry }) conflict("재사용 보호자 신청이 변경되었습니다.")
        if (waitlist.isDeleted(definition)) conflict("삭제된 프로그램입니다.")
        val now = waitlist.now()
        // 현장 접수는 사전 모집 기간이 아니라 회차의 현장 접수 기간에 연다.
        val (opensAt, closesAt) = policy.admissionWindow(command.onsite) ?: conflict("현장 접수 기간이 설정되지 않은 프로그램입니다.")
        if (policy.closed || policy.capacity <= 0 || now.isBefore(opensAt) || !now.isBefore(closesAt) ||
            snapshots.values.any { now.isBefore(it.startedAt) || !now.isBefore(it.endedAt) }
        ) {
            conflict("프로그램 또는 폼 신청 기간이 아닙니다.")
        }
        if (ids.any { ledger.hasTimeConflict(definition, it) }) {
            throw ProgramApplicationConflictException("신청한 프로그램과 운영 시간이 겹칩니다.", ProgramIneligibleReason.TIME_CONFLICT)
        }
        newItems.forEach { item ->
            val individual = requireNotNull(commands[item.participantId])
            if (ledger.activeInGroup(individual, definition.groupId)) conflict("같은 묶음에 활성 신청이 있습니다.")
            if (command.key.programType == ProgramSessionType.STANDARD) {
                val definitions =
                    gateway.programDefinitions(
                        definition.expoId,
                        definition.programType,
                        standards.findAllByParticipantId(item.participantId).map { it.standardProgramId },
                        existing = true,
                    )
                if (definitions.any { it.groupId == definition.groupId }) conflict("같은 묶음에 기존 신청이 있습니다.")
            }
        }
        waitlist.promote(definition, now)
        val needed = newItems.size
        // 현장 묶음은 신규 인원 전체가 현장 좌석에 들어갈 때만 확정하고, 사전 대기열 뒤에 서거나 대기하지 않는다.
        if (command.onsite &&
            (
                ledger.occupiedCount(definition) + needed > policy.capacity ||
                    ledger.onsiteRemaining(definition) < needed
            )
        ) {
            conflict("일괄 신청을 수용할 현장 접수 좌석이 없습니다.")
        }
        val waiting =
            !command.onsite &&
                (
                    repository.waitingCount(definition, channel?.name) > 0 ||
                        ledger.occupiedCount(definition) + needed > policy.capacity ||
                        (policy.hasAdmissionCapacities() && ledger.preRegistrationRemaining(definition) < needed) ||
                        (channel != null && repository.confirmedCount(definition, channel.name) + needed > channel.capacity)
                )
        if (waiting &&
            (
                repository.waitingCount(definition, channel?.name) + needed > (channel?.waitingCapacity ?: policy.waitingCapacity) ||
                    !waitlist.now().isBefore(policy.promotionEndedAt)
            )
        ) {
            conflict("일괄 신청을 수용할 대기 정원이 없거나 대기 접수가 종료되었습니다.")
        }
        val traineeIds =
            if (command.key.programType == ProgramSessionType.TRAINING) {
                newItems
                    .associate { item ->
                        item.participantId to
                            gateway.resolveTrainee(
                                definition.expoId,
                                item.participantId,
                                requireNotNull(snapshots[item.participantId]).trainingId(requireNotNull(item.answers)),
                            )
                    }.also { resolved ->
                        if (resolved.values.toSet().size != resolved.size) conflict("서로 다른 참가자가 같은 연수자로 연결되었습니다.")
                        resolved.values.sorted().forEach { id ->
                            if (!trainings.tryLockTrainee(id)) conflict("연수 신청 변경 중입니다.")
                            val definitions =
                                gateway.programDefinitions(
                                    definition.expoId,
                                    definition.programType,
                                    trainings.findAllByTraineeId(id).map { it.trainingProgramId },
                                    existing = true,
                                )
                            if (definitions.any { it.groupId == definition.groupId }) conflict("같은 묶음에 기존 연수 신청이 있습니다.")
                        }
                    }
            } else {
                emptyMap()
            }
        if (!waitlist.now().isBefore(closesAt) ||
            snapshots.values.any { !waitlist.now().isBefore(it.endedAt) } ||
            (waiting && !waitlist.now().isBefore(policy.promotionEndedAt))
        ) {
            conflict("프로그램 또는 폼 접수 기간이 끝났습니다.")
        }
        val applications =
            request.participants.map { item ->
                val old = existing[item.participantId]
                StudentGuardianBatchApplication(
                    if (old != null) {
                        ProgramSessionReceipt(old.id, definition.id, old.participantId, old.traineeId, old.status)
                    } else {
                        ProgramSessionReceipt(
                            UUID.randomUUID(),
                            definition.id,
                            item.participantId,
                            traineeIds[item.participantId].takeUnless { waiting },
                            if (waiting) "WAITING" else "CONFIRMED",
                        )
                    },
                    old != null,
                )
            }
        val receipt = StudentGuardianBatchReceipt(batchId, applications)
        batches.save(command, owner, receipt)
        applications.filterNot { it.reused }.forEach { item ->
            val result = item.application
            if (!waiting) {
                if (definition.programType == ProgramSessionType.STANDARD) {
                    standards.saveAndFlush(
                        StandardProgramApplication(participantId = result.participantId, standardProgramId = definition.id),
                    )
                } else {
                    val trainee = requireNotNull(result.traineeId)
                    operations.lockVersion(trainee)
                    trainings.saveAndFlush(TrainingProgramApplication(traineeId = trainee, trainingProgramId = definition.id))
                    operations.incrementVersion(trainee)
                }
            }
            ledger.save(
                requireNotNull(commands[result.participantId]),
                definition,
                requireNotNull(snapshots[result.participantId]),
                result,
                channel?.name,
            )
            repository.event(result.applicationId, result.status)
            batches.addMember(batchId, result.applicationId, result.participantId in guardians)
        }
        val byPerson = applications.associate { it.application.participantId to it.application.applicationId }
        request.links.forEach { batches.link(requireNotNull(byPerson[it.studentId]), requireNotNull(byPerson[it.guardianId])) }
        return receipt
    }

    private fun conflict(reason: String): Nothing = throw ProgramApplicationConflictException(reason)

    private fun unavailable(): Nothing =
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "학생·보호자 학년·정책·시간 충돌 계약 공급자가 준비되지 않았습니다.")
}
