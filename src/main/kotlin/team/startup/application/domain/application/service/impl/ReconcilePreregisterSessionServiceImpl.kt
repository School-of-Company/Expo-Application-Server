package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.ReconcilePreregisterSessionService

@Service
class ReconcilePreregisterSessionServiceImpl(
    private val ledger: PreregisterSessionLedger,
    private val repository: PreregisterSessionRepository,
) : ReconcilePreregisterSessionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: PreregisterSessionKey): Boolean {
        if (command.sessionId <= 0) badSessionRequest("회차 ID가 올바르지 않습니다.")
        val pending =
            ledger.transactions.execute {
                ledger.lock(command.expoId)
                repository.pending(command.expoId, command.sessionId)
            } ?: return true
        val observation = ledger.observe(command.expoId, command.sessionId)
        return requireNotNull(
            ledger.transactions.execute {
                ledger.lock(command.expoId)
                val currentPending = repository.pending(command.expoId, command.sessionId)
                if (currentPending == null) return@execute true
                if (currentPending != pending) return@execute false
                ledger.reconcile(command.expoId, command.sessionId, observation)
            },
        )
    }
}
