package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.CancelPreregisterSessionService

@Service
class CancelPreregisterSessionServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
) : CancelPreregisterSessionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelPreregisterSessionCommand): List<PreregisterApplicationReceipt> {
        if (command.sessionId <= 0 || command.applicationIds.isEmpty() || command.applicationIds.any { it <= 0 } ||
            command.applicationIds.distinct().size != command.applicationIds.size ||
            (command.representativeId != null && command.representativeId <= 0)
        ) {
            badSessionRequest("취소할 신청 목록이 올바르지 않습니다.")
        }
        ledger.transactions.executeWithoutResult {
            ledger.lock(command.expoId)
            ledger.ensureAvailable(command.expoId, command.sessionId)
        }
        val observation = ledger.observe(command.expoId, command.sessionId)
        return requireNotNull(
            ledger.transactions.execute {
                ledger.lock(command.expoId)
                val definition = ledger.current(command.expoId, command.sessionId, observation)
                val owned = repository.selectedOwned(command.expoId, command.sessionId, command.applicationIds)
                if (owned.size != command.applicationIds.size ||
                    owned.any {
                        command.representativeId != null && it.second != command.representativeId
                    }
                ) {
                    conflict("취소할 신청의 소속이 일치하지 않습니다.")
                }
                val selected = owned.map { it.first }
                val cancelled =
                    selected.filter { it.status != "CANCELLED" }.map {
                        repository.status(it.id, "CANCELLED")
                        it.copy(status = "CANCELLED")
                    }
                val promoted =
                    if (selected.any { it.status == "CONFIRMED" } &&
                        repository.autoPromote(command.expoId, command.sessionId)
                    ) {
                        ledger.promote(definition, automatic = true)
                    } else {
                        emptyList()
                    }
                cancelled + promoted
            },
        )
    }
}
