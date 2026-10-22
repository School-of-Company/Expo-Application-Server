package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOffer
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import java.time.Instant

// Shared #144 offer rules: who owns an offer, which rows it holds, and how a decline or expiry ends the wait.
@Component
class PromotionOffers(
    private val offers: PromotionOfferRepository,
    private val applications: ProgramWaitlistRepository,
    private val batches: StudentGuardianBatchRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val waitlist: ProgramWaitlist,
    private val reservations: PreregisterReservationRepository,
    private val sessions: PreregisterSessionRepository,
    private val notifications: ApplicationNotifications,
) {
    // The single waiting row, or every waiting member of the guardian batch the offer holds seats for.
    fun members(offer: PromotionOffer): List<ProgramWaitlistRepository.Entry> =
        offer.batchId?.let { batch -> batches.members(batch).filter { it.status == "WAITING" } }
            ?: listOfNotNull(applications.entry(requireNotNull(offer.applicationId)).takeIf { it?.status == "WAITING" })

    fun snapshot(offer: PromotionOffer): ProgramSessionDefinition = batches.definition(requireNotNull(offer.applicationId))

    fun reservation(offer: PromotionOffer): PreregisterReservation? =
        reservations.find(offer.expoId, requireNotNull(offer.requestId), "").takeIf { it?.queueSeq != null }

    // Program offers belong to the managed party of the waiting participant; batches also to their stored representative.
    fun ownsProgram(
        offer: PromotionOffer,
        party: OwnerApplicationParty,
    ): Boolean {
        val head = applications.entry(requireNotNull(offer.applicationId)) ?: return false
        val ids = party.participants.map { it.participantId }.toSet()
        return head.participantId in ids || (offer.batchId != null && batches.representative(offer.batchId) == party.representativeId)
    }

    fun ownsReservation(
        reservation: PreregisterReservation,
        phone: String,
    ): Boolean = reservation.userRequest.phoneNumber.filter { it in '0'..'9' } == phone

    // Participant locks first (sorted, like submissions), then the schedule/group/program locks, then the offer row.
    fun lockProgram(
        offer: PromotionOffer,
        definition: ProgramSessionDefinition,
    ): PromotionOffer? {
        val members = members(offer)
        (members.map { it.participantId } + batches.links(members).map { it.guardianId })
            .toSet()
            .sorted()
            .forEach(ledger::lockParticipant)
        waitlist.lock(definition)
        return offers.lock(offer.id)
    }

    // Decline and no-response expiry end the wait as a terminal cancellation. Delivery failure never comes here.
    fun endProgramWait(
        offer: PromotionOffer,
        state: String,
        actorType: ApplicationActorType,
        actorParticipantId: Long?,
    ) {
        members(offer).forEach {
            applications.transition(it.id, "CANCELLED", actorType, actorParticipantId = actorParticipantId)
            // 거절(DECLINED)은 본인이 답한 것이라 안내하지 않는다. 실제 EXPIRED 전이만 만료 안내를 쌓는다.
            if (state == "EXPIRED") notifications.programOfferExpired(offer.expoId, it.id)
        }
        offers.close(offer.id, state, actorType.name, actorParticipantId)
    }

    fun endPreregisterWait(
        offer: PromotionOffer,
        reservation: PreregisterReservation,
        state: String,
        actorType: ApplicationActorType,
    ) {
        reservations.withdraw(reservation, actorType)
        if (state == "EXPIRED") notifications.reservationOfferExpired(offer.expoId, reservation.requestId, reservation.sessionId)
        offers.close(offer.id, state, actorType.name)
    }

    fun owner(
        offer: PromotionOffer,
        now: Instant,
    ): OwnerPromotionOffer =
        OwnerPromotionOffer(
            offer.id,
            offer.target,
            when {
                offer.respondable(now) -> "OPEN"
                offer.state in listOf("ACCEPTED", "DECLINED", "EXPIRED") -> offer.state
                offer.state == "VOIDED" || !now.isBefore(offer.hardDeadline) -> "ENDED"
                else -> "PENDING"
            },
            offer.respondable(now),
            offer.expiresAt,
            offer.programType,
            offer.programId,
            offer.sessionId,
            if (offer.target ==
                "PROGRAM"
            ) {
                snapshot(offer).title
            } else {
                sessions.definition(offer.expoId, requireNotNull(offer.sessionId))?.title
            },
            offer.seats,
        )

    fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
