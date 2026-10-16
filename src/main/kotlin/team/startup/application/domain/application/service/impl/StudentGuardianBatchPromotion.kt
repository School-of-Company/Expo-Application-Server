package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
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
            (channel != null && applications.confirmedCount(definition, channel) + members.size > policy.channels.orEmpty().single { it.name == channel }.capacity)
        ) {
            return 0
        }
        val trainees = mutableMapOf<Long, Long>()
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
            if (ids.any { !batches.confirmedPreregister(head.key, it) }) return 0
            for (entry in members) {
                val saved = batches.definition(entry.id)
                if (saved.groupId != definition.groupId) return 0
                if (definition.programType == ProgramSessionType.TRAINING) {
                    trainees[entry.participantId] = gateway.resolveTrainee(definition.expoId, entry.participantId, programTrainingId(entry.questions, entry.answers))
                } else {
                    val existing = gateway.programDefinitions(
                        definition.expoId, definition.programType,
                        standards.findAllByParticipantId(entry.participantId).map { it.standardProgramId }, existing = true,
                    )
                    if (existing.any { it.groupId == definition.groupId }) return 0
                }
            }
            if (trainees.values.toSet().size != trainees.size) return 0
            for (trainee in trainees.values.sorted()) {
                if (!trainings.tryLockTrainee(trainee)) return 0
                val existing = gateway.programDefinitions(
                    definition.expoId, definition.programType, trainings.findAllByTraineeId(trainee).map { it.trainingProgramId }, existing = true,
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
}
