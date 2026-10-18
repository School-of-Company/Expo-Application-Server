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
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

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
    private val offers: PromotionOfferRepository,
    private val offerPolicy: PromotionOfferPolicy,
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
        offers.capProgram(definition, minOf(policy.applicationEndedAt, policy.promotionEndedAt))
        if (!policy.autoPromotionOpen(now)) return
        if (isDeleted(definition)) return
        if (offerPolicy.offerMode(offerPolicy.programStart(definition.startedAt), now)) return offer(definition, now)
        // shortcut: 승급당 최대 10명으로 원격 호출 잠금을 제한한다. 일괄 연결 API가 제공되면 교체한다.
        var budget = 10
        // 채널마다 따로 FIFO로 승급한다. 다른 채널의 빈자리는 쓰지 않고, 회차 합계 정원도 함께 지킨다.
        channels@ for (channel in policy.channels ?: listOf(null)) {
            val remaining = minOf(free(definition, channel?.name), budget.toLong())
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
                if (!policy.autoPromotionOpen(promotionTime)) return
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

    // Seats still free for the queue after confirmed rows and seats held by active offers (#144).
    fun free(
        definition: ProgramSessionDefinition,
        channel: String?,
        excludingOffer: UUID? = null,
    ): Long {
        val policy = requireNotNull(definition.policy)
        return minOf(
            policy.capacity - ledger.confirmedCount(definition.programType, definition.id) -
                offers.programHeld(definition, excluding = excludingOffer),
            channel?.let { name ->
                val capacity =
                    policy.channels
                        .orEmpty()
                        .single { it.name == name }
                        .capacity
                capacity - repository.confirmedCount(definition, name) - offers.programHeld(definition, name, excludingOffer)
            } ?: Long.MAX_VALUE,
            // 분리 회차의 대기는 사전 좌석으로만 승급한다. 현장 좌석은 쓰지 않는다.
            if (policy.hasAdmissionCapacities()) ledger.preRegistrationRemaining(definition, excludingOffer) else Long.MAX_VALUE,
        )
    }

    // From the day before the session, a vacancy only holds seats for the queue head and schedules an offer; the
    // representative's acceptance confirms. Strict FIFO per channel, whole guardian batches, no remote calls.
    private fun offer(
        definition: ProgramSessionDefinition,
        now: Instant,
    ) {
        val policy = requireNotNull(definition.policy)
        val hardDeadline = minOf(policy.applicationEndedAt, policy.promotionEndedAt)
        val sendAfter = offerPolicy.sendAfter(now)
        // No usable send interval before the existing cutoffs means no offer and no confirmation.
        if (!sendAfter.isBefore(hardDeadline)) return
        channels@ for (channel in policy.channels ?: listOf(null)) {
            var free = free(definition, channel?.name)
            if (free <= 0) continue
            val offered = mutableSetOf<UUID>()
            for (entry in repository.waiting(definition, free.toInt(), channel?.name)) {
                val batch = guardianBatches.ifAvailable?.offerMembers(entry)
                if (batch != null && !offered.add(batch.first)) continue
                val seats = batch?.second ?: 1
                if (seats <= 0) continue
                if (seats > free) continue@channels
                offers.createProgram(definition, entry.id, batch?.first, channel?.name, seats, sendAfter, hardDeadline)
                free -= seats
            }
        }
    }

    // Acceptance runs the promotion checks again under the same locks. A failed check never cancels the waiting row;
    // the offer stays respondable until it expires. Returns null on confirmation, otherwise the rejection reason.
    fun acceptSingle(
        definition: ProgramSessionDefinition,
        entry: ProgramWaitlistRepository.Entry,
        offerId: UUID,
        actorParticipantId: Long,
    ): String? = confirm(definition, entry, offerId, ApplicationActorType.USER, null, actorParticipantId)

    // #172: an admin confirms an operator-selected waiting row after automatic promotion stopped, with the same checks.
    // Seats held by other active offers stay held, and a failed check keeps the row waiting.
    fun confirmManually(
        definition: ProgramSessionDefinition,
        entry: ProgramWaitlistRepository.Entry,
        adminId: Long,
    ): String? = confirm(definition, entry, null, ApplicationActorType.ADMIN, adminId, null)

    private fun confirm(
        definition: ProgramSessionDefinition,
        entry: ProgramWaitlistRepository.Entry,
        offerId: UUID?,
        actorType: ApplicationActorType,
        actorId: Long?,
        actorParticipantId: Long?,
    ): String? {
        val policy = requireNotNull(definition.policy)
        val now = now()
        if (offerId == null) {
            policy.manualPromotionBlock(now)?.let { return it }
        } else if (policy.closed || !now.isBefore(policy.applicationEndedAt) || !now.isBefore(policy.promotionEndedAt)) {
            return "PROMOTION_ENDED"
        }
        if (isDeleted(definition)) return "PROGRAM_DELETED"
        if (!repository.confirmedPreregister(entry)) return "PREREGISTRATION_NOT_CONFIRMED"
        if (free(definition, repository.channel(entry.id), offerId) < 1) return "CAPACITY_CHANGED"
        if (ledger.hasTimeConflict(definition, entry.participantId)) return "TIME_CONFLICT"
        val traineeId =
            if (definition.programType == ProgramSessionType.TRAINING) {
                val traineeId =
                    try {
                        gateway.resolveTrainee(definition.expoId, entry.participantId, programTrainingId(entry.questions, entry.answers))
                    } catch (ex: ResponseStatusException) {
                        if (ex.statusCode == HttpStatus.NOT_FOUND || ex.statusCode == HttpStatus.CONFLICT) return "TRAINEE_UNAVAILABLE"
                        throw ex
                    }
                if (!trainings.tryLockTrainee(traineeId)) return "BUSY"
                val existing = trainings.findAllByTraineeId(traineeId).map { it.trainingProgramId }
                if (existing.isNotEmpty() &&
                    gateway.programDefinitions(definition.expoId, definition.programType, existing, existing = true).any {
                        it.groupId == definition.groupId
                    }
                ) {
                    return "GROUP_ALREADY_APPLIED"
                }
                traineeId
            } else {
                null
            }
        // User/Expo calls above can cross the final deadline; a manual confirmation never lands after it.
        if (offerId == null) policy.manualPromotionBlock(now())?.let { return it }
        if (traineeId != null) {
            operations.lockVersion(traineeId)
            trainings.saveAndFlush(TrainingProgramApplication(traineeId = traineeId, trainingProgramId = definition.id))
            operations.incrementVersion(traineeId)
        } else {
            standards.saveAndFlush(StandardProgramApplication(participantId = entry.participantId, standardProgramId = definition.id))
        }
        repository.transition(entry.id, "CONFIRMED", actorType, traineeId, actorId, actorParticipantId)
        return null
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
