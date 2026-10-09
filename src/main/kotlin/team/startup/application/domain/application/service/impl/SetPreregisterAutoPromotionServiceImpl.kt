package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.SetPreregisterAutoPromotionCommand
import team.startup.application.domain.application.service.SetPreregisterAutoPromotionService

@Service
class SetPreregisterAutoPromotionServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
) : SetPreregisterAutoPromotionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: SetPreregisterAutoPromotionCommand) {
        val (expoId, sessionId) = command.session
        ledger.transactions.executeWithoutResult {
            ledger.lock(expoId)
            ledger.ensureAvailable(expoId, sessionId)
        }
        val observation = ledger.observe(expoId, sessionId)
        ledger.transactions.executeWithoutResult {
            ledger.lock(expoId)
            ledger.current(expoId, sessionId, observation)
            if (command.enabled && !repository.autoPromote(expoId, sessionId)) conflict("OFF→ON 공석 처리 정책이 확정되지 않았습니다.")
            repository.setAutoPromote(expoId, sessionId, command.enabled)
        }
    }
}
