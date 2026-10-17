package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.StudentGuardianEligibilityQuery
import team.startup.application.domain.application.presentation.dto.StudentGuardianValidationPhase
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import java.time.Clock

@Component
class StudentGuardianBatchPromotion(
    private val batches: StudentGuardianBatchRepository,
    private val applications: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val gateway: RegistrationGateway,
    private val eligibility: ObjectProvider<StudentGuardianEligibilityProvider>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun promote(
        definition: ProgramSessionDefinition,
        head: ProgramWaitlistRepository.Entry,
    ): Int? {
        val batchId = batches.batchId(head.id) ?: return null
        if (applications.entry(head.id)?.status != "WAITING") return -1
        val members = batches.members(batchId).filter { it.status == "WAITING" }
        val links = batches.links(members)
        val ids = (members.map { it.participantId } + links.map { it.guardianId }).toSet()
        if (!ids.sorted().all(ledger::tryLockParticipant)) return 0
        val provider = eligibility.ifAvailable ?: return 0
        val policy = requireNotNull(definition.policy)
        val channel = batches.channel(head.id)
        if (members.any { batches.channel(it.id) != channel } ||
            ledger.confirmedCount(definition.programType, definition.id) + members.size > policy.capacity ||
            (policy.hasAdmissionCapacities() && ledger.preRegistrationRemaining(definition) < members.size) ||
            (
                channel != null &&
                    applications.confirmedCount(definition, channel) + members.size >
                    policy.channels
                        .orEmpty()
                        .single { it.name == channel }
                        .capacity
            )
        ) {
            return 0
        }
        val trainees = mutableMapOf<Long, Long>()
        try {
            if (ids.any { ledger.hasTimeConflict(definition, it) }) return 0
            try {
                provider.validate(
                    StudentGuardianEligibilityQuery(
                        definition,
                        ids,
                        links,
                        StudentGuardianValidationPhase.PROMOTION,
                        links.map { it.guardianId }.toSet() - members.map { it.participantId }.toSet(),
                    ),
                )
            } catch (ex: ResponseStatusException) {
                // 자격·폼 거부(4xx)는 재시도해도 같으므로 묶음을 내린다. 5xx·연결 실패만 선두를 보류한다(#146).
                if (ex.statusCode.is4xxClientError) return cancel(definition, members, "ELIGIBILITY_${ex.statusCode.value()}")
                return 0
            } catch (_: ProgramApplicationConflictException) {
                return cancel(definition, members, "ELIGIBILITY_CONFLICT")
            }
            if (ids.any { !batches.confirmedPreregister(head.key, it) }) return 0
            for (entry in members) {
                val saved = batches.definition(entry.id)
                if (saved.groupId != definition.groupId) return cancel(definition, members, "DEFINITION_MISMATCH")
                if (definition.programType == ProgramSessionType.TRAINING) {
                    trainees[entry.participantId] =
                        try {
                            gateway.resolveTrainee(
                                definition.expoId,
                                entry.participantId,
                                programTrainingId(entry.questions, entry.answers),
                            )
                        } catch (ex: ResponseStatusException) {
                            // 단건 승급과 같이 User 404/409만 영구 실패로 본다(#116).
                            if (ex.statusCode == HttpStatus.NOT_FOUND || ex.statusCode == HttpStatus.CONFLICT) {
                                return cancel(definition, members, "USER_${ex.statusCode.value()}")
                            }
                            return 0
                        }
                } else {
                    val existing =
                        gateway.programDefinitions(
                            definition.expoId,
                            definition.programType,
                            standards.findAllByParticipantId(entry.participantId).map { it.standardProgramId },
                            existing = true,
                        )
                    if (existing.any { it.groupId == definition.groupId }) return 0
                }
            }
            if (trainees.values.toSet().size != trainees.size) return 0
            for (trainee in trainees.values.sorted()) {
                if (!trainings.tryLockTrainee(trainee)) return 0
                val existing =
                    gateway.programDefinitions(
                        definition.expoId,
                        definition.programType,
                        trainings.findAllByTraineeId(trainee).map { it.trainingProgramId },
                        existing = true,
                    )
                if (existing.any { it.groupId == definition.groupId }) return 0
            }
        } catch (_: ResponseStatusException) {
            return 0
        } catch (_: ProgramApplicationConflictException) {
            return 0
        }
        if (!clock.instant().isBefore(policy.applicationEndedAt) || !clock.instant().isBefore(policy.promotionEndedAt)) return 0
        members.forEach { entry ->
            val trainee = trainees[entry.participantId]
            if (trainee == null) {
                standards.saveAndFlush(StandardProgramApplication(participantId = entry.participantId, standardProgramId = definition.id))
            } else {
                operations.lockVersion(trainee)
                trainings.saveAndFlush(TrainingProgramApplication(traineeId = trainee, trainingProgramId = definition.id))
                operations.incrementVersion(trainee)
            }
            applications.transition(entry.id, "CONFIRMED", ApplicationActorType.SYSTEM, trainee)
        }
        return members.size
    }

    // shortcut: 단건 승급과 같이 빈자리는 다음 승급 계기에서 채운다. -1을 돌려 같은 조회의 다음 대기자로 넘어간다.
    private fun cancel(
        definition: ProgramSessionDefinition,
        members: List<ProgramWaitlistRepository.Entry>,
        reason: String,
    ): Int {
        members.forEach { applications.transition(it.id, "CANCELLED", ApplicationActorType.SYSTEM) }
        log.warn(
            "프로그램 묶음 대기 취소: {} / {} / {} ({}, {})",
            definition.expoId,
            definition.programType,
            definition.id,
            members.map { it.id },
            reason,
        )
        return -1
    }
}
