package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey
import team.startup.application.domain.application.service.PromotePreregisterSessionService

@Service
class PromotePreregisterSessionServiceImpl(
    private val ledger: PreregisterSessionLedger,
) : PromotePreregisterSessionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: PreregisterSessionKey): List<PreregisterApplicationReceipt> {
        ledger.transactions.executeWithoutResult {
            ledger.lock(command.expoId)
            ledger.ensureAvailable(command.expoId, command.sessionId)
        }
        val observation = ledger.observe(command.expoId, command.sessionId)
        return requireNotNull(
            ledger.transactions.execute {
                ledger.lock(command.expoId)
                ledger.promote(ledger.current(command.expoId, command.sessionId, observation), automatic = false)
            },
        )
    }
}
