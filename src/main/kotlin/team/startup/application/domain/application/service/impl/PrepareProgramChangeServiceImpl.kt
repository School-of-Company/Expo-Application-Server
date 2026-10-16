package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand
import team.startup.application.domain.application.presentation.dto.hasValidPlace
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.PrepareProgramChangeService
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class PrepareProgramChangeServiceImpl(
    private val repository: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val standards: StandardProgramApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val mapper: ObjectMapper,
) : PrepareProgramChangeService {
    @Transactional
    override fun execute(command: PrepareProgramChangeCommand) {
        repository.lockChange(command.expoId)
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
                        !policy.channelsValid()
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
            val waiting = repository.waitingCount(old)
            if ((next == null && (active > 0 || confirmed > 0)) ||
                (next != null && (next.groupId != old.groupId || next.policy == null) && (active > 0 || confirmed > 0)) ||
                (next?.policy != null && (next.policy.capacity < confirmed || next.policy.waitingCapacity < waiting))
            ) {
                conflict()
            }
            // Existing applications are never reclassified: with any history the channel layout is frozen,
            // and each channel may only shrink down to its own confirmed and waiting rows.
            next?.policy?.let { policy ->
                if ((active > 0 || confirmed > 0) && policy.channelLayout() != old.policy?.channelLayout()) conflict()
                if (policy.channels.orEmpty().any {
                        it.capacity < repository.confirmedCount(old, it.name) || it.waitingCapacity < repository.waitingCount(old, it.name)
                    }
                ) {
                    conflict()
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

    private fun badRequest(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 프로그램 변경 정의입니다.")
}
