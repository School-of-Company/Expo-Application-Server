package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.exception.SessionChangeConflictCode
import team.startup.application.domain.application.exception.SessionChangeConflictException
import team.startup.application.domain.application.presentation.dto.PreparePreregisterSessionChangeCommand
import team.startup.application.domain.application.presentation.dto.PreregisterChangeOperation
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.isValidDefinition
import team.startup.application.domain.application.presentation.dto.sameAs
import team.startup.application.domain.application.presentation.dto.targeted
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.PreparePreregisterSessionChangeService

@Service
class PreparePreregisterSessionChangeServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val reservations: PreregisterReservationRepository,
) : PreparePreregisterSessionChangeService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: PreparePreregisterSessionChangeCommand) {
        val request = command.request
        if (command.sessionId <= 0 || command.nextRevision <= 1) badSessionRequest("변경 식별자가 올바르지 않습니다.")
        if (ledger.transactions.execute {
                ledger.lock(command.expoId)
                if (replay(command)) return@execute true
                ledger.ensureAvailable(command.expoId, command.sessionId)
                false
            } == true
        ) {
            return
        }
        val observation = ledger.observe(command.expoId, command.sessionId)
        ledger.transactions.executeWithoutResult {
            ledger.lock(command.expoId)
            if (replay(command)) return@executeWithoutResult
            val current = ledger.current(command.expoId, command.sessionId, observation)
            if (current.revision == Long.MAX_VALUE || command.nextRevision != current.revision + 1) conflict("회차 버전이 일치하지 않습니다.")
            var nonTimeOnly = false
            val definitionChanged =
                when (request.operation) {
                    PreregisterChangeOperation.DELETE -> {
                        if (request.definition != null || !request.definitionChanged) badSessionRequest("삭제 정의가 올바르지 않습니다.")
                        true
                    }

                    PreregisterChangeOperation.UPDATE -> {
                        val next = request.definition ?: badSessionRequest("변경할 전체 정의가 필요합니다.")
                        if (next.expoId != command.expoId || next.id != command.sessionId || next.revision != command.nextRevision ||
                            !next.isValidDefinition()
                        ) {
                            badSessionRequest("변경할 회차 정의가 올바르지 않습니다.")
                        }
                        // An unadopted waiting capacity cannot be compared, so trust Expo's flag and keep the history block.
                        val changed =
                            !current.copy(closed = next.closed, revision = next.revision).sameAs(next) ||
                                (current.waitingCapacity == null && request.definitionChanged)
                        if (changed != request.definitionChanged) badSessionRequest("정의 변경 여부가 일치하지 않습니다.")
                        // #268: with history only the times stay locked; an unadopted waiting capacity stays unapplied on reconcile.
                        nonTimeOnly =
                            current.startedAt == next.startedAt && current.endedAt == next.endedAt &&
                            current.cancelEndedAt == next.cancelEndedAt &&
                            (current.waitingCapacity == null || next.waitingCapacity != null)
                        if (nonTimeOnly && changed) capacityFits(command, current, next)
                        changed
                    }
                }
            if (definitionChanged && !nonTimeOnly && repository.history(command.expoId, command.sessionId)) {
                conflict(
                    if (request.operation == PreregisterChangeOperation.DELETE) {
                        SessionChangeConflictCode.STRUCTURE_LOCKED_BY_HISTORY
                    } else {
                        SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY
                    },
                )
            }
            repository.prepare(command.expoId, command.sessionId, command.nextRevision, request)
        }
    }

    // #173: capacities may change after applications only while they still hold every confirmed and reserved seat.
    // Unknown seats count against both categories, and target sessions take no waiting party.
    // #268: an adopted waiting capacity may not drop below the waiting applicants; their order never changes.
    private fun capacityFits(
        command: PreparePreregisterSessionChangeCommand,
        current: PreregisterSessionDefinition,
        next: PreregisterSessionDefinition,
    ) {
        if (next.capacity <
            repository.occupied(command.expoId, command.sessionId)
        ) {
            conflict(SessionChangeConflictCode.BELOW_CONFIRMED_COUNT)
        }
        val waiting = reservations.waitingApplicants(command.expoId, command.sessionId)
        if (current.waitingCapacity != null &&
            requireNotNull(next.waitingCapacity) < waiting
        ) {
            conflict(SessionChangeConflictCode.BELOW_WAITING_COUNT)
        }
        if (!next.targeted) return
        if (waiting > 0) conflict(SessionChangeConflictCode.STRUCTURE_LOCKED_BY_HISTORY)
        val (teacher, other) = PreregisterSeatCategory.remaining(next, repository.seatUsage(command.expoId, command.sessionId))
        if (teacher < 0 || other < 0) conflict(SessionChangeConflictCode.BELOW_CONFIRMED_COUNT)
    }

    private fun conflict(code: SessionChangeConflictCode): Nothing = throw SessionChangeConflictException(code)

    private fun replay(command: PreparePreregisterSessionChangeCommand): Boolean {
        val saved = repository.change(command.expoId, command.sessionId, command.nextRevision) ?: return false
        if (!saved.sameAs(command.request)) conflict("같은 회차 변경 키의 내용이 다릅니다.")
        return true
    }
}
