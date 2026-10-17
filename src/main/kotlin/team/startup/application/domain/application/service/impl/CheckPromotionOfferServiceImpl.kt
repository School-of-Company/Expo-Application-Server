package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PromotionOfferCheck
import team.startup.application.domain.application.presentation.dto.PromotionOfferEvent
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.CheckPromotionOfferService
import java.time.Clock
import java.time.format.DateTimeFormatter

// Notification asks right before the irreversible provider request. Remote lookups run without a transaction and the
// local state is checked again afterwards, like the promotion notice check.
@Service
class CheckPromotionOfferServiceImpl(
    private val offers: PromotionOfferRepository,
    private val shared: PromotionOffers,
    private val sessions: PreregisterSessionRepository,
    private val gateway: ProgramPromotionGateway,
    private val policy: PromotionOfferPolicy,
    private val clock: Clock,
) : CheckPromotionOfferService {
    @Transactional(propagation = Propagation.NEVER)
    override fun execute(event: PromotionOfferEvent): PromotionOfferCheck {
        ineligible(event)?.let { return it }
        val offer = requireNotNull(offers.find(event.offerId))
        val check =
            if (offer.target == "PROGRAM") {
                val head = shared.members(offer).firstOrNull() ?: return PromotionOfferCheck(false, NOT_CURRENT)
                val (phone, name) = gateway.representativeContact(offer.expoId, head.participantId)
                val notice = gateway.programNotice(offer.expoId, requireNotNull(offer.programType), requireNotNull(offer.programId))
                eligible(offer, phone, name, notice.title, notice.startedAt, notice.endedAt, notice.location)
            } else {
                val reservation = shared.reservation(offer) ?: return PromotionOfferCheck(false, NOT_CURRENT)
                val session =
                    sessions.definition(offer.expoId, requireNotNull(offer.sessionId))
                        ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "사전등록 회차 정의가 없습니다.")
                val phone =
                    reservation.userRequest.phoneNumber
                        .filter { it in '0'..'9' }
                        .takeIf { it.matches(Regex("0[0-9]{8,10}")) }
                eligible(
                    offer,
                    phone,
                    reservation.userRequest.name,
                    session.title,
                    session.startedAt.atZone(PromotionOfferPolicy.KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    session.endedAt.atZone(PromotionOfferPolicy.KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    session.place,
                )
            }
        ineligible(event)?.let { return it }
        return check
    }

    // SCHEDULED with the same event ID means Kafka delivered it before the publisher recorded the ACK.
    private fun ineligible(event: PromotionOfferEvent): PromotionOfferCheck? {
        val offer = offers.find(event.offerId) ?: return PromotionOfferCheck(false, NOT_CURRENT)
        if (offer.eventId != event.eventId || offer.expoId != event.expoId || offer.state !in listOf("SCHEDULED", "PUBLISHED")) {
            return PromotionOfferCheck(false, NOT_CURRENT)
        }
        val now = clock.instant()
        if (!now.isBefore(offer.hardDeadline)) return PromotionOfferCheck(false, NOT_CURRENT)
        if (offer.target == "PROGRAM" && shared.members(offer).isEmpty()) return PromotionOfferCheck(false, NOT_CURRENT)
        if (offer.target == "PREREGISTER" && shared.reservation(offer)?.state != "WAITING") return PromotionOfferCheck(false, NOT_CURRENT)
        if (!policy.inWindow(now)) return PromotionOfferCheck(false, "OUTSIDE_SEND_WINDOW")
        return null
    }

    private fun eligible(
        offer: PromotionOffer,
        phone: String?,
        name: String,
        title: String,
        startedAt: String,
        endedAt: String,
        location: String?,
    ) = PromotionOfferCheck(
        eligible = true,
        eventId = offer.eventId,
        offerId = offer.id,
        expoId = offer.expoId,
        target = offer.target,
        representativePhoneNumber = phone,
        recipientName = name,
        title = title,
        startedAt = startedAt,
        endedAt = endedAt,
        location = location,
        seats = offer.seats,
        responseUrl = policy.responseUrl(offer.expoId),
        hardDeadline = offer.hardDeadline,
        sendWindowEndsAt = minOf(policy.windowEnd(clock.instant()), offer.hardDeadline),
        responseHours = PromotionOfferPolicy.RESPONSE.toHours().toInt(),
    )

    private companion object {
        const val NOT_CURRENT = "NOT_CURRENT_OFFER"
    }
}
