package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import team.startup.application.domain.application.time.ExpoProgramTime
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

// #144 approved policy (2026-10-17). Offers start at 00:00 KST the day before the session date, are sent only from 09:00
// (inclusive) to 18:00 (exclusive) KST, and allow a response until provider acceptance + 6 hours, capped by the existing
// hard cutoffs. Night is not excluded from the 6 hours and cutoffs are never extended.
@Component
class PromotionOfferPolicy(
    @Value("\${application.promotion-offers.enabled:false}") val enabled: Boolean,
    @Value("\${application.promotion-offers.response-url:}") private val responseUrl: String,
) {
    init {
        if (enabled) {
            val uri = runCatching { URI.create(responseUrl.replace("{expoId}", UUID(0, 0).toString())) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && responseUrl.length <= 400) {
                "APPLICATION_PROMOTION_OFFERS_RESPONSE_URL must be an https URL when offers are enabled"
            }
        }
    }

    // Disabled keeps the pre-#144 automatic promotion; enabling requires the Notification offer consumer and Client page.
    fun offerMode(
        sessionStart: Instant?,
        now: Instant,
    ): Boolean = enabled && (sessionStart == null || !now.isBefore(switchAt(sessionStart)))

    fun programStart(startedAt: String?): Instant? = startedAt?.let { runCatching { ExpoProgramTime.parse(it).toInstant() }.getOrNull() }

    fun switchAt(sessionStart: Instant): Instant =
        sessionStart
            .atZone(KST)
            .toLocalDate()
            .minusDays(1)
            .atStartOfDay(KST)
            .toInstant()

    fun inWindow(now: Instant): Boolean = now.atZone(KST).toLocalTime().let { !it.isBefore(OPEN) && it.isBefore(CLOSE) }

    fun windowEnd(now: Instant): Instant = now.atZone(KST).with(CLOSE).toInstant()

    // A night vacancy waits for the next 09:00 KST.
    fun sendAfter(now: Instant): Instant {
        val local = now.atZone(KST)
        return when {
            local.toLocalTime().isBefore(OPEN) -> local.with(OPEN).toInstant()
            !local.toLocalTime().isBefore(CLOSE) -> local.plusDays(1).with(OPEN).toInstant()
            else -> now
        }
    }

    fun expiresAt(
        providerAcceptedAt: Instant,
        hardDeadline: Instant,
    ): Instant = minOf(providerAcceptedAt.plus(RESPONSE), hardDeadline)

    fun responseUrl(expoId: UUID): String = responseUrl.replace("{expoId}", expoId.toString())

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val RESPONSE: Duration = Duration.ofHours(6)
        private val OPEN: LocalTime = LocalTime.of(9, 0)
        private val CLOSE: LocalTime = LocalTime.of(18, 0)
    }
}
