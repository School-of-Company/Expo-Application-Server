package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.PreparePreregisterSessionChangeCommand
import team.startup.application.domain.application.presentation.dto.PreregisterChangeOperation
import team.startup.application.domain.application.presentation.dto.isValidDefinition
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.PreparePreregisterSessionChangeService

@Service
class PreparePreregisterSessionChangeServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
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
                        val changed = current.copy(closed = next.closed, revision = next.revision) != next
                        if (changed != request.definitionChanged) badSessionRequest("정의 변경 여부가 일치하지 않습니다.")
                        changed
                    }
                }
            if (definitionChanged && repository.history(command.expoId, command.sessionId)) conflict("신청 이력이 있는 회차입니다.")
            repository.prepare(command.expoId, command.sessionId, command.nextRevision, request)
        }
    }

    private fun replay(command: PreparePreregisterSessionChangeCommand): Boolean {
        val saved = repository.change(command.expoId, command.sessionId, command.nextRevision) ?: return false
        if (saved != command.request) conflict("같은 회차 변경 키의 내용이 다릅니다.")
        return true
    }
}
