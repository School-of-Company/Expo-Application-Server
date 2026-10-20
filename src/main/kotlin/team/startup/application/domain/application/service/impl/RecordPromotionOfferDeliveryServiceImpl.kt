package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryCommand
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryResult
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryStatus
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.RecordPromotionOfferDeliveryService
import java.time.Clock
import java.time.Duration

// Notification reports what the provider actually did. ACCEPTED with a real provider acceptance time starts the
// response deadline; FAILED keeps that deadline, or starts report time + 6 hours when the provider never accepted, and
// then expires like no response (#226). UNKNOWN keeps the seats until the hard cutoff. Reports are idempotent and a
// stale event ID cannot change a newer attempt.
@Service
class RecordPromotionOfferDeliveryServiceImpl(
    private val offers: PromotionOfferRepository,
    private val policy: PromotionOfferPolicy,
    private val clock: Clock,
) : RecordPromotionOfferDeliveryService {
    @Transactional
    override fun execute(command: PromotionOfferDeliveryCommand): PromotionOfferDeliveryResult {
        val request = command.request
        val offer = offers.lock(command.offerId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (offer.eventId != request.eventId) throw ResponseStatusException(HttpStatus.CONFLICT, "현재 제안 이벤트가 아닙니다.")
        val now = clock.instant()
        when (requireNotNull(request.status)) {
            PromotionOfferDeliveryStatus.ACCEPTED -> {
                val attemptId = request.attemptId ?: badRequest()
                val acceptedAt = request.providerAcceptedAt ?: badRequest()
                if (acceptedAt.isAfter(now.plus(SKEW)) || (request.attemptedAt != null && acceptedAt.isBefore(request.attemptedAt))) {
                    badRequest()
                }
                when {
                    offer.providerAcceptedAt != null -> {
                        if (offer.attemptId != attemptId || offer.providerAcceptedAt != acceptedAt) conflict()
                    }

                    offer.state !in SENDABLE + "UNKNOWN" -> {
                        Unit
                    }

                    // Accepted on or after the hard cutoff leaves no usable response interval: no confirmation is possible.
                    !acceptedAt.isBefore(offer.hardDeadline) -> {
                        offers.close(offer.id, "VOIDED", null, voidReason = "NO_RESPONSE_INTERVAL")
                    }

                    else -> {
                        offers.accepted(
                            offer.id,
                            attemptId,
                            request.attemptedAt,
                            acceptedAt,
                            policy.expiresAt(acceptedAt, offer.hardDeadline),
                        )
                    }
                }
            }

            PromotionOfferDeliveryStatus.UNKNOWN -> {
                if (offer.state in
                    SENDABLE
                ) {
                    offers.delivery(offer.id, "UNKNOWN", request.attemptId, request.attemptedAt, "UNKNOWN", request.reason)
                }
            }

            PromotionOfferDeliveryStatus.FAILED -> {
                if (offer.state in SENDABLE + listOf("UNKNOWN", "SENT")) {
                    offers.delivery(
                        offer.id,
                        "DELIVERY_FAILED",
                        request.attemptId,
                        request.attemptedAt,
                        "FAILED",
                        request.reason,
                        // At or after the hard cutoff no response interval is left; the sweeper only releases the hold.
                        policy.expiresAt(now, offer.hardDeadline).takeIf { now.isBefore(offer.hardDeadline) },
                    )
                }
            }

            PromotionOfferDeliveryStatus.DELIVERED -> {
                if (offer.state == "SENT") offers.delivery(offer.id, "SENT", null, null, "DELIVERED", null)
            }

            // Skipped before any provider request; outside the send window it goes to the next 09:00 if still usable.
            PromotionOfferDeliveryStatus.SKIPPED -> {
                if (offer.state in SENDABLE && request.reason == "OUTSIDE_SEND_WINDOW" &&
                    !offers.reschedule(offer.id, policy.sendAfter(now))
                ) {
                    offers.close(offer.id, "VOIDED", null, voidReason = "NO_SEND_INTERVAL")
                }
            }
        }
        return PromotionOfferDeliveryResult(offer.id, requireNotNull(offers.find(offer.id)).state)
    }

    private fun badRequest(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "공급자 접수 결과가 올바르지 않습니다.")

    private fun conflict(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 접수 결과가 기록되었습니다.")

    private companion object {
        val SKEW: Duration = Duration.ofMinutes(1)

        // SCHEDULED too: a crash after the Kafka ACK but before marking PUBLISHED still delivers the same event ID.
        val SENDABLE = listOf("SCHEDULED", "PUBLISHED")
    }
}
