package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOffer
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOfferCommand
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.DeclinePromotionOfferService
import java.time.Clock

// A decline is the representative's explicit choice, so it ends the wait in any active offer state and the next
// party in strict FIFO is offered right away. Repeating a decline returns the same result.
@Service
class DeclinePromotionOfferServiceImpl(
    private val gateway: RegistrationGateway,
    private val offers: PromotionOfferRepository,
    private val shared: PromotionOffers,
    private val applications: ProgramWaitlistRepository,
    private val waitlist: ProgramWaitlist,
    private val preregisterWaitlist: PreregisterWaitlist,
    private val ledger: PreregisterSessionLedger,
    private val clock: Clock,
) : DeclinePromotionOfferService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: OwnerPromotionOfferCommand): OwnerPromotionOffer {
        val offer = offers.find(requireNotNull(command.offerId))?.takeIf { it.expoId == command.expoId } ?: shared.notFound()
        if (offer.target == "PROGRAM") {
            val party = gateway.managedParty(command.expoId, command.participantSession)
            if (!shared.ownsProgram(offer, party)) shared.notFound()
            val representativeId = party.representativeId ?: shared.notFound()
            val definition =
                gateway.programDefinition(offer.expoId.toString(), requireNotNull(offer.programType), requireNotNull(offer.programId))
                    ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정의 공급자가 준비되지 않았습니다.")
            ledger.transactions.executeWithoutResult {
                val current = shared.lockProgram(offer, definition) ?: shared.notFound()
                if (current.state == "DECLINED") return@executeWithoutResult
                declinable(current)
                applications.observe(definition)
                shared.endProgramWait(current, "DECLINED", ApplicationActorType.USER, representativeId)
                waitlist.promote(definition)
            }
        } else {
            val phone = gateway.participantSessionPhone(command.expoId.toString(), command.participantSession)
            val reservation = shared.reservation(offer)?.takeIf { shared.ownsReservation(it, phone) } ?: shared.notFound()
            ledger.transactions.executeWithoutResult {
                ledger.lock(offer.expoId)
                val current = offers.lock(offer.id) ?: shared.notFound()
                if (current.state == "DECLINED") return@executeWithoutResult
                declinable(current)
                val waiting = shared.reservation(current)?.takeIf { it.requestId == reservation.requestId && it.state == "WAITING" }
                shared.endPreregisterWait(current, waiting ?: conflict("OFFER_TARGET_CHANGED"), "DECLINED", ApplicationActorType.USER)
            }
            preregisterWaitlist.promoteQuietly(offer.expoId, requireNotNull(offer.sessionId))
        }
        return shared.owner(requireNotNull(offers.find(offer.id)), clock.instant())
    }

    private fun declinable(offer: PromotionOffer) {
        if (!offer.active) conflict("OFFER_${offer.state}")
        if (offer.expiresAt != null && !clock.instant().isBefore(offer.expiresAt)) conflict("OFFER_EXPIRED")
    }

    private fun conflict(reason: String): Nothing = throw ProgramApplicationConflictException(reason)
}
