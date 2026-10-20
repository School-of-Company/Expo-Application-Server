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
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.AcceptPromotionOfferService
import java.time.Clock
import java.time.Instant

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
    private val sessions: PreregisterSessionRepository,
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
            if (waiting?.state != "WAITING") conflict("OFFER_TARGET_CHANGED")
            requireNoConflictingSession(waiting, definition, now)
            if (!reservations.promote(waiting)) conflict("OFFER_TARGET_CHANGED")
            offers.close(current.id, "ACCEPTED", ApplicationActorType.USER.name)
        }
        return shared.owner(requireNotNull(offers.find(offer.id)), clock.instant())
    }

    // #223: 수락은 승급 처리로 이어지므로 완료 단계에서 겹침으로 거절되기 전에 여기서 막는다. 같은 일행이 이미 확정했거나 선점한
    // 겹치는 회차, 이미 신청한 같은 회차가 있으면 대기를 그대로 두고 409로 답한다. 다른 대기는 세지 않는다(승급은 아직 일어나지 않았다).
    // 동행자 추가 예약은 일행 전체가 아니므로 검사하지 않는다.
    private fun requireNoConflictingSession(
        waiting: PreregisterReservation,
        definition: PreregisterSessionDefinition,
        now: Instant,
    ) {
        if (waiting.companionOf != null) return
        val expoId = waiting.expoId
        val phone = waiting.userRequest.phoneNumber.filter { it in '0'..'9' }
        val ids = waiting.result?.participantIds
        if (ids != null && ids.any { sessions.active(expoId, waiting.sessionId, it) != null }) conflict("GROUP_ALREADY_APPLIED")
        val confirmed =
            if (ids != null) {
                sessions.overlapping(expoId, waiting.sessionId, ids, definition)
            } else {
                reservations.overlappingConfirmedByPhone(expoId, waiting.sessionId, phone, definition).isNotEmpty()
            }
        if (confirmed || reservations.overlappingPending(expoId, waiting.sessionId, phone, ids, definition, now, listOf("RESERVED"))) {
            conflict("TIME_CONFLICT")
        }
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
