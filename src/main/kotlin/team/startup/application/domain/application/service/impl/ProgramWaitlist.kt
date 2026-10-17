package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
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
    private val guardianBatches: ObjectProvider<StudentGuardianBatchPromotion>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun now(): Instant = clock.instant()

    fun lock(definition: ProgramSessionDefinition) {
        ledger.lockSchedule(definition.expoId)
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
                    // 분리 회차의 대기는 사전 좌석으로만 승급한다. 현장 좌석은 쓰지 않는다.
                    if (policy.hasAdmissionCapacities()) ledger.preRegistrationRemaining(definition) else Long.MAX_VALUE,
                    budget.toLong(),
                )
            if (remaining <= 0) continue
            for (entry in repository.waiting(definition, remaining.toInt(), channel?.name)) {
                val promoted = guardianBatches.ifAvailable?.promote(definition, entry)
                if (promoted != null) {
                    if (promoted == 0) continue@channels
                    if (promoted > 0) budget -= promoted
                    if (budget <= 0) return
                    continue
                }
                // Submission takes participant before group locks, so promotion must never wait for a participant.
                if (!ledger.tryLockParticipant(entry.participantId)) continue@channels
                try {
                    if (ledger.hasTimeConflict(definition, entry.participantId)) continue@channels
                } catch (_: ResponseStatusException) {
                    defer(definition, "EXPO_UNAVAILABLE")
                    continue@channels
                }
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
                repository.transition(entry.id, "CONFIRMED", ApplicationActorType.SYSTEM, traineeId)
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
            } catch (ex: ResponseStatusException) {
                // User 404/409는 재시도해도 성공하지 않으므로 대기에서 내려 다음 순번이 차례대로 승급되게 한다(#116).
                // shortcut: 빈자리는 이번 회차가 아니라 다음 승급 계기(스케줄러·취소·신청)에서 채운다.
                if (ex.statusCode == HttpStatus.NOT_FOUND || ex.statusCode == HttpStatus.CONFLICT) {
                    repository.transition(entry.id, "CANCELLED", ApplicationActorType.SYSTEM)
                    log.warn(
                        "프로그램 대기 취소: {} / {} / {} ({}, USER_{})",
                        definition.expoId,
                        definition.programType,
                        definition.id,
                        entry.id,
                        ex.statusCode.value(),
                    )
                    return null
                }
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
