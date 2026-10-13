package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityResponse
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetPreregisterSessionCapacityService

@Service
class GetPreregisterSessionCapacityServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val reservations: PreregisterReservationRepository,
    @Value("\${application.registration.waitlist-intake-enabled:false}") private val waitlistIntake: Boolean,
) : GetPreregisterSessionCapacityService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(query: PreregisterSessionCapacityQuery): PreregisterSessionCapacityResponse {
        if (query.sessionId <= 0) badSessionRequest("회차 ID가 올바르지 않습니다.")
        val observation = ledger.observe(query.expoId, query.sessionId)
        val reads = TransactionTemplate(requireNotNull(ledger.transactions.transactionManager)).apply { isReadOnly = true }
        return requireNotNull(
            reads.execute {
                repository.readLock(query.expoId)
                if (repository.expoDeleted(query.expoId)) conflict("삭제된 박람회입니다.")
                val definition = ledger.current(query.expoId, query.sessionId, observation, persist = false)
                val occupied = repository.occupied(query.expoId, query.sessionId)
                val waiting = reservations.waitingApplicants(query.expoId, query.sessionId)
                val waitingCapacity = definition.waitingCapacity
                // New parties queue behind waiting ones, so no confirmed seat is offered while the queue is not empty.
                val remaining =
                    if (definition.closed || waiting > 0) {
                        0
                    } else {
                        (definition.capacity.toLong() - occupied).coerceAtLeast(0).toInt()
                    }
                val waitingRemaining = ((waitingCapacity ?: 0) - waiting).coerceAtLeast(0).toInt()
                val maxWaiting = if (definition.closed || !waitlistIntake) 0 else minOf(waitingRemaining, 5)
                PreregisterSessionCapacityResponse(
                    query.sessionId,
                    remaining,
                    minOf(remaining, 5),
                    occupied,
                    waiting,
                    waitingCapacity,
                    waitingRemaining,
                    maxWaiting,
                    if (waitingCapacity == null) "UNADOPTED" else "ADOPTED",
                    observation?.waitingCapacity,
                )
            },
        )
    }
}
