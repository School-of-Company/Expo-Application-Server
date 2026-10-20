package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistsService

// A waiting party has no User participant yet, so the verified session phone identifies the owner. Latest first (#198).
// #252: direct reservations still being saved are PROCESSING too; reviewRequired marks those held for an operator.
@Service
class GetOwnerPreregisterWaitlistsServiceImpl(
    private val gateway: RegistrationGateway,
    private val reservations: PreregisterReservationRepository,
    private val sessions: PreregisterSessionRepository,
    private val waitlist: PreregisterWaitlist,
    transactionManager: PlatformTransactionManager,
) : GetOwnerPreregisterWaitlistsService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: OwnerPreregisterWaitlistCommand): List<OwnerPreregisterWaitlist> {
        val phone = gateway.participantSessionPhone(command.expoId.toString(), command.participantSession)
        return requireNotNull(
            reads.execute {
                reservations
                    .pendingByPhone(command.expoId, phone)
                    .filter { command.sessionId == null || it.sessionId == command.sessionId }
                    .map {
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
                            // needsReview is the only RESERVED state with an error_status (#222).
                            it.state == "RESERVED" && it.errorStatus != null,
                        )
                    }
            },
        )
    }
}
