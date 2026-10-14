package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import java.time.Clock
import java.time.Instant

@Component
class ProgramWaitlist(
    private val repository: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val gateway: RegistrationGateway,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun now(): Instant = clock.instant()

    fun lock(definition: ProgramSessionDefinition) {
        ledger.lockGroups(listOf(definition))
        if (definition.programType ==
            ProgramSessionType.STANDARD
        ) {
            standards.lockProgram(definition.id)
        } else {
            trainings.lockTrainingProgram(definition.id)
        }
    }

    fun isDeleted(definition: ProgramSessionDefinition): Boolean =
        if (definition.programType ==
            ProgramSessionType.STANDARD
        ) {
            standards.isDeleted(definition.id)
        } else {
            trainings.isDeleted(definition.id)
        }

    fun promote(
        definition: ProgramSessionDefinition,
        now: Instant = now(),
    ) {
        val policy = definition.policy ?: return
        if (policy.closed || now.isBefore(policy.applicationStartedAt) || !now.isBefore(policy.applicationEndedAt) ||
            !now.isBefore(policy.promotionEndedAt)
        ) {
            return
        }
        if (isDeleted(definition)) return
        // shortcut: 승급당 최대 10명으로 원격 호출 잠금을 제한한다. 일괄 연결 API가 제공되면 교체한다.
        var budget = 10
        // 채널마다 따로 FIFO로 승급한다. 다른 채널의 빈자리는 쓰지 않고, 회차 합계 정원도 함께 지킨다.
        channels@ for (channel in policy.channels ?: listOf(null)) {
            val remaining =
                minOf(
                    policy.capacity - ledger.confirmedCount(definition.programType, definition.id),
                    channel?.let { (it.capacity - repository.confirmedCount(definition, it.name)).toLong() } ?: Long.MAX_VALUE,
                    budget.toLong(),
                )
            if (remaining <= 0) continue
            for (entry in repository.waiting(definition, remaining.toInt(), channel?.name)) {
                val traineeId =
                    if (definition.programType ==
                        ProgramSessionType.TRAINING
                    ) {
                        promotionTrainee(definition, entry) ?: continue@channels
                    } else {
                        null
                    }
                val promotionTime = now()
                if (!promotionTime.isBefore(policy.applicationEndedAt) || !promotionTime.isBefore(policy.promotionEndedAt)) return
                if (traineeId != null) {
                    operations.lockVersion(traineeId)
                    trainings.saveAndFlush(TrainingProgramApplication(traineeId = traineeId, trainingProgramId = definition.id))
                    operations.incrementVersion(traineeId)
                } else {
                    standards.saveAndFlush(
                        StandardProgramApplication(participantId = entry.participantId, standardProgramId = definition.id),
                    )
                }
                repository.transition(entry.id, "CONFIRMED", traineeId)
                budget--
            }
        }
    }

    private fun promotionTrainee(
        definition: ProgramSessionDefinition,
        entry: ProgramWaitlistRepository.Entry,
    ): Long? {
        val traineeId =
            try {
                gateway.resolveTrainee(definition.expoId, entry.participantId, programTrainingId(entry.questions, entry.answers))
            } catch (_: ResponseStatusException) {
                return defer(definition, "USER_UNAVAILABLE")
            }
        if (!trainings.tryLockTrainee(traineeId)) return defer(definition, "TRAINEE_BUSY")
        val existing = trainings.findAllByTraineeId(traineeId).map { it.trainingProgramId }
        if (existing.isNotEmpty()) {
            val definitions =
                try {
                    gateway.programDefinitions(definition.expoId, definition.programType, existing, existing = true)
                } catch (_: ResponseStatusException) {
                    return defer(definition, "EXPO_UNAVAILABLE")
                }
            if (definitions.any { it.groupId == definition.groupId }) return defer(definition, "LEGACY_GROUP_CONFLICT")
        }
        return traineeId
    }

    private fun defer(
        definition: ProgramSessionDefinition,
        reason: String,
    ): Long? {
        log.warn("프로그램 승급 보류: {} / {} / {} ({})", definition.expoId, definition.programType, definition.id, reason)
        return null
    }
}
