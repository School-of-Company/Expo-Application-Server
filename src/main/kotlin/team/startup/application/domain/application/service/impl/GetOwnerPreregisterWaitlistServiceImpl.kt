package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistService

// A waiting party has no User participant yet, so the verified session phone identifies the owner.
@Service
class GetOwnerPreregisterWaitlistServiceImpl(
    private val gateway: RegistrationGateway,
    private val reservations: PreregisterReservationRepository,
    private val sessions: PreregisterSessionRepository,
    private val waitlist: PreregisterWaitlist,
    transactionManager: PlatformTransactionManager,
) : GetOwnerPreregisterWaitlistService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: OwnerPreregisterWaitlistCommand): OwnerPreregisterWaitlist {
        val phone = gateway.participantSessionPhone(command.expoId.toString(), command.participantSession)
        return reads.execute {
            reservations.waitlistedByPhone(command.expoId, phone)?.let {
                val waiting = it.state == "WAITING"
                val ended = waiting && sessions.definition(command.expoId, it.sessionId)?.let(waitlist::accepts) == false
                OwnerPreregisterWaitlist(
                    it.sessionId,
                    it.applicants,
                    if (ended) {
                        "PROMOTION_ENDED"
                    } else if (waiting) {
                        "WAITING"
                    } else {
                        "PROCESSING"
                    },
                    if (waiting && !ended) reservations.position(it) else null,
                )
            }
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}
