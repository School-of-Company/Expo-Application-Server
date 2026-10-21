package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.exception.SessionChangeConflictCode
import team.startup.application.domain.application.exception.SessionChangeConflictException
import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand
import team.startup.application.domain.application.presentation.dto.hasValidPlace
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.PrepareProgramChangeService
import team.startup.application.domain.application.time.ExpoProgramTime
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class PrepareProgramChangeServiceImpl(
    private val repository: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val mapper: ObjectMapper,
    private val offers: PromotionOfferRepository,
) : PrepareProgramChangeService {
    @Transactional
    override fun execute(command: PrepareProgramChangeCommand) {
        repository.lockChange(command.expoId)
        ledger.lockSchedule(command.expoId.toString(), changing = true)
        ledger.requireAttributionReady(command.expoId)
        val payload = mapper.writeValueAsString(command.payload)
        if (repository.changeReplay(command.expoId, command.changeId, payload)) return
        val mutations = command.payload.mutations
        if (mutations.isEmpty() || mutations.size > 1000 || mutations.any { it.previous == null }) badRequest()
        val previous = mutations.map { requireNotNull(it.previous) }
        if (previous.map { it.programType to it.id }.distinct().size != previous.size) badRequest()
        for (mutation in mutations) {
            val old = requireNotNull(mutation.previous)
            if (UUID.fromString(old.expoId) != command.expoId || old.revision <= 0 || old.id <= 0) badRequest()
            if (old.pendingRevision != null || old.revision == Long.MAX_VALUE || !old.hasValidPlace()) badRequest()
            mutation.definition?.let { next ->
                if (next.expoId != old.expoId || next.programType != old.programType || next.id != old.id ||
                    next.revision != old.revision + 1 ||
                    next.pendingRevision != null || !next.hasValidPlace()
                ) {
                    badRequest()
                }
                next.policy?.let { policy ->
                    if (policy.capacity <= 0 || policy.waitingCapacity < 0 || policy.allowedOccupations.isEmpty() ||
                        (policy.groupId != null && policy.groupId != next.groupId) ||
                        !policy.applicationStartedAt.isBefore(policy.applicationEndedAt) ||
                        policy.cancelEndedAt.isBefore(policy.applicationStartedAt) ||
                        policy.promotionEndedAt.isBefore(policy.applicationStartedAt) ||
                        !policy.channelsValid() || !policy.admissionCapacitiesValid() || policy.guardianPolicy?.valid() == false ||
                        !policy.promotionSettingsValid()
                    ) {
                        badRequest()
                    }
                    if (listOf(policy.applicationStartedAt, policy.applicationEndedAt, policy.cancelEndedAt, policy.promotionEndedAt).any {
                            it.nano %
                                1000 !=
                                0
                        }
                    ) {
                        badRequest()
                    }
                }
            }
        }
        ledger.lockGroups(previous + mutations.mapNotNull { it.definition })
        previous
            .map { it.id }
            .distinct()
            .sorted()
            .forEach(standards::lockProgram)
        for (mutation in mutations) {
            val old = requireNotNull(mutation.previous)
            val next = mutation.definition
            val active = repository.activeCount(old)
            val confirmed = ledger.confirmedCount(old.programType, old.id)
            // Seats held by active participation offers (#144) cannot be removed by reconfiguration either.
            val offered = ledger.offeredSeats(old)
            val waiting = repository.waitingCount(old)
            val history = active > 0 || confirmed > 0
            // #268: with history the times, group and channel layout stay locked; other policy fields, including the
            // guardian policy, may change. Rows admitted before a guardian policy change are not revalidated on promotion.
            if (history &&
                (next == null || next.groupId != old.groupId || next.policy == null)
            ) {
                conflict(SessionChangeConflictCode.STRUCTURE_LOCKED_BY_HISTORY)
            }
            if (history && next != null &&
                !(ExpoProgramTime.same(next.startedAt, old.startedAt) && ExpoProgramTime.same(next.endedAt, old.endedAt))
            ) {
                conflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY)
            }
            next?.policy?.let { policy ->
                if (policy.capacity < confirmed + offered) conflict(SessionChangeConflictCode.BELOW_CONFIRMED_COUNT)
                if (policy.waitingCapacity < waiting) conflict(SessionChangeConflictCode.BELOW_WAITING_COUNT)
            }
            // Existing applications are never reclassified: with any history the channel layout is frozen,
            // and each channel may only shrink down to its own confirmed and waiting rows.
            next?.policy?.let { policy ->
                if (old.policy?.hasAdmissionCapacities() == true && !policy.hasAdmissionCapacities()) conflict()
                if (policy.hasAdmissionCapacities()) {
                    if (old.policy?.hasAdmissionCapacities() != true &&
                        waiting > 0
                    ) {
                        conflict(SessionChangeConflictCode.STRUCTURE_LOCKED_BY_HISTORY)
                    }
                    val unknown = ledger.unknownAdmissionCount(old)
                    if (requireNotNull(
                            policy.preRegistrationCapacity,
                        ) < ledger.admissionConfirmedCount(old, "PRE_REGISTRATION") + unknown + offered ||
                        requireNotNull(policy.onsiteCapacity) < ledger.admissionConfirmedCount(old, "ONSITE") + unknown
                    ) {
                        conflict(SessionChangeConflictCode.BELOW_CONFIRMED_COUNT)
                    }
                }
                if (history &&
                    policy.channelLayout() != old.policy?.channelLayout()
                ) {
                    conflict(SessionChangeConflictCode.STRUCTURE_LOCKED_BY_HISTORY)
                }
                policy.channels.orEmpty().forEach {
                    if (it.capacity < repository.confirmedCount(old, it.name) + offers.programHeld(old, it.name)) {
                        conflict(SessionChangeConflictCode.BELOW_CONFIRMED_COUNT)
                    }
                    if (it.waitingCapacity < repository.waitingCount(old, it.name)) conflict(SessionChangeConflictCode.BELOW_WAITING_COUNT)
                }
            }
            repository.stage(old, next, command.changeId)
            if (next == null) {
                if (old.programType.name == "STANDARD") standards.markDeleted(old.id) else trainings.markDeleted(old.id)
            }
        }
        repository.saveChange(command.expoId, command.changeId, payload)
    }

    private fun conflict(): Nothing = throw ProgramApplicationConflictException("활성 신청 또는 정원 때문에 프로그램을 변경할 수 없습니다.")

    private fun conflict(code: SessionChangeConflictCode): Nothing = throw SessionChangeConflictException(code)

    private fun badRequest(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 프로그램 변경 정의입니다.")
}
