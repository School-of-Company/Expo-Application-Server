package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityResponse
import team.startup.application.domain.application.presentation.dto.PreregisterTargetSeats
import team.startup.application.domain.application.presentation.dto.targeted
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetPreregisterSessionCapacityService

@Service
class GetPreregisterSessionCapacityServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val reservations: PreregisterReservationRepository,
    private val gateway: RegistrationGateway,
    @Value("\${application.registration.waitlist-intake-enabled:false}") private val waitlistIntake: Boolean,
) : GetPreregisterSessionCapacityService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(query: PreregisterSessionCapacityQuery): PreregisterSessionCapacityResponse {
        if (query.sessionId <= 0) badSessionRequest("회차 ID가 올바르지 않습니다.")
        val observation = ledger.observe(query.expoId, query.sessionId)
        // An expired or invalid session only loses the owner view; the public seats stay readable.
        val party =
            query.participantSession?.takeIf { observation?.targeted == true }?.let {
                try {
                    gateway.managedParty(query.expoId, it)
                } catch (_: ParticipantSessionException) {
                    null
                }
            }
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
                if (definition.targeted) {
                    // #173: totals would reveal teacher seats, so only the non-teacher count and teacher availability are public.
                    val (teacher, other) =
                        PreregisterSeatCategory.remaining(definition, repository.seatUsage(query.expoId, query.sessionId)).let { (t, o) ->
                            if (remaining == 0) 0L to 0L else t.coerceIn(0, remaining.toLong()) to o.coerceIn(0, remaining.toLong())
                        }
                    val owner =
                        party?.participants?.map { it.participantId }?.let {
                            repository.confirmedSeatCategories(query.expoId, query.sessionId, it)
                        }
                    return@execute PreregisterSessionCapacityResponse(
                        query.sessionId,
                        null,
                        null,
                        null,
                        waiting,
                        waitingCapacity,
                        0,
                        0,
                        if (waitingCapacity == null) "UNADOPTED" else "ADOPTED",
                        observation?.waitingCapacity,
                        PreregisterTargetSeats(
                            teacher > 0,
                            other.toInt(),
                            minOf(other.toInt(), 5),
                            teacher.toInt().takeIf { owner?.contains(PreregisterSeatCategory.TEACHER) == true },
                        ),
                    )
                }
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
