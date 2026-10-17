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
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.AcceptPromotionOfferService
import java.time.Clock

// The authenticated representative's explicit acceptance is the only way an offer confirms (#144). Session, User and
// Expo/Form lookups run before the local transaction; the existing promotion checks run again under the locks.
@Service
class AcceptPromotionOfferServiceImpl(
    private val gateway: RegistrationGateway,
    private val offers: PromotionOfferRepository,
    private val shared: PromotionOffers,
    private val applications: ProgramWaitlistRepository,
    private val waitlist: ProgramWaitlist,
    private val guardianBatches: StudentGuardianBatchPromotion,
    private val reservations: PreregisterReservationRepository,
    private val preregisterWaitlist: PreregisterWaitlist,
    private val ledger: PreregisterSessionLedger,
    private val clock: Clock,
) : AcceptPromotionOfferService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: OwnerPromotionOfferCommand): OwnerPromotionOffer {
        val offer = offers.find(requireNotNull(command.offerId))?.takeIf { it.expoId == command.expoId } ?: shared.notFound()
        return if (offer.target == "PROGRAM") program(command, offer) else preregister(command, offer)
    }

    private fun program(
        command: OwnerPromotionOfferCommand,
        offer: PromotionOffer,
    ): OwnerPromotionOffer {
        val party = gateway.managedParty(command.expoId, command.participantSession)
        if (!shared.ownsProgram(offer, party)) shared.notFound()
        val representativeId = party.representativeId ?: shared.notFound()
        val definition =
            gateway.programDefinition(offer.expoId.toString(), requireNotNull(offer.programType), requireNotNull(offer.programId))
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정의 공급자가 준비되지 않았습니다.")
        ledger.transactions.executeWithoutResult {
            val current = shared.lockProgram(offer, definition) ?: shared.notFound()
            applications.observe(definition)
            if (current.state == "ACCEPTED") return@executeWithoutResult
            respondable(current)
            val head = shared.members(current).firstOrNull() ?: conflict("OFFER_TARGET_CHANGED")
            val reason =
                if (current.batchId != null) {
                    guardianBatches.accept(definition, head, current.id, representativeId)
                } else {
                    waitlist.acceptSingle(definition, head, current.id, representativeId)
                }
            reject(reason)
            offers.close(current.id, "ACCEPTED", ApplicationActorType.USER.name, representativeId)
        }
        return shared.owner(requireNotNull(offers.find(offer.id)), clock.instant())
    }

    // Acceptance moves the party to RESERVED; the existing recovery then saves it to User with its fixed request ID,
    // which is what issues the QR. Nothing reaches User or Attendance before this explicit acceptance.
    private fun preregister(
        command: OwnerPromotionOfferCommand,
        offer: PromotionOffer,
    ): OwnerPromotionOffer {
        val phone = gateway.participantSessionPhone(command.expoId.toString(), command.participantSession)
        val reservation = shared.reservation(offer)?.takeIf { shared.ownsReservation(it, phone) } ?: shared.notFound()
        val observation = ledger.observe(offer.expoId, requireNotNull(offer.sessionId))
        val applicationEndedAt = preregisterWaitlist.applicationEndedAt(offer.expoId)
        ledger.transactions.executeWithoutResult {
            ledger.lock(offer.expoId)
            val current = offers.lock(offer.id) ?: shared.notFound()
            if (current.state == "ACCEPTED") return@executeWithoutResult
            respondable(current)
            val definition = ledger.current(offer.expoId, offer.sessionId, observation)
            val now = clock.instant()
            if (definition.closed || !now.isBefore(applicationEndedAt) || !now.isBefore(definition.startedAt)) conflict("PROMOTION_ENDED")
            val waiting = reservations.find(offer.expoId, reservation.requestId, "")
            if (waiting?.state != "WAITING" || !reservations.promote(waiting)) conflict("OFFER_TARGET_CHANGED")
            offers.close(current.id, "ACCEPTED", ApplicationActorType.USER.name)
        }
        return shared.owner(requireNotNull(offers.find(offer.id)), clock.instant())
    }

    private fun respondable(offer: PromotionOffer) {
        if (!offer.respondable(clock.instant())) conflict(if (offer.active) "OFFER_NOT_RESPONDABLE" else "OFFER_${offer.state}")
    }

    // Transient failures leave everything unchanged for a retry; the rest stay open until the deadline.
    private fun reject(reason: String?) {
        when (reason) {
            null -> return
            "BUSY", "UNAVAILABLE" -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason)
            else -> conflict(reason)
        }
    }

    private fun conflict(reason: String): Nothing = throw ProgramApplicationConflictException(reason)
}
