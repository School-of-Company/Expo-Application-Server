package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdoptPreregisterWaitingCapacityCommand
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.AdoptPreregisterWaitingCapacityService

// Sessions with applications before #99 keep no waiting capacity until an operator confirms the current Expo value.
@Service
class AdoptPreregisterWaitingCapacityServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
) : AdoptPreregisterWaitingCapacityService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AdoptPreregisterWaitingCapacityCommand) {
        if (command.sessionId <= 0 || command.expected < 0) badSessionRequest("대기 정원 채택 요청이 올바르지 않습니다.")
        val observation = ledger.observe(command.expoId, command.sessionId)
        ledger.transactions.executeWithoutResult {
            ledger.lock(command.expoId)
            ledger.current(command.expoId, command.sessionId, observation)
            if (observation?.waitingCapacity != command.expected) conflict("Expo의 현재 대기 정원과 기대값이 다릅니다.")
            val stored = repository.definition(command.expoId, command.sessionId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
            if (stored.waitingCapacity == null) repository.saveDefinition(stored.copy(waitingCapacity = command.expected))
        }
    }
}
