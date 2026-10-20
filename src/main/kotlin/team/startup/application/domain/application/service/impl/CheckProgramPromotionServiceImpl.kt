package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramPromotionCheck
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent
import team.startup.application.domain.application.repository.ProgramPromotionRepository
import team.startup.application.domain.application.service.CheckProgramPromotionService
import java.time.Clock
import java.time.OffsetDateTime

// Notification retries a check it cannot parse forever (#244), so text is normalized to its labels like the offer check
// (#236). Its schema has no failure reason, so a name or title with nothing sendable skips the notice as not current.
@Service
class CheckProgramPromotionServiceImpl(
    private val repository: ProgramPromotionRepository,
    private val gateway: ProgramPromotionGateway,
    private val policy: ProgramPromotionPolicy,
    private val clock: Clock,
) : CheckProgramPromotionService {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NEVER)
    override fun execute(event: ProgramPromotionEvent): ProgramPromotionCheck {
        ineligible(event)?.let { return it }
        val details = gateway.details(event)
        ineligible(event)?.let { return it }
        if (!clock.instant().isBefore(OffsetDateTime.parse(requireNotNull(details.startedAt)).toInstant())) {
            return ProgramPromotionCheck(false, NOT_CURRENT)
        }
        val participantName = label(requireNotNull(details.participantName), 100)
        val programName = label(requireNotNull(details.programName), 100)
        if (participantName == null || programName == null) {
            log.warn("Program promotion notice has no sendable name or title: {}", event.applicationId)
            return ProgramPromotionCheck(false, NOT_CURRENT)
        }
        // A place Notification cannot show is left out of the message rather than cut.
        return details.copy(
            participantName = participantName,
            programName = programName,
            location = details.location?.let { label(it, 255) },
        )
    }

    private fun ineligible(event: ProgramPromotionEvent): ProgramPromotionCheck? {
        val promotion =
            repository.find(event.applicationId)
                ?: return ProgramPromotionCheck(false, NOT_CURRENT)
        if (promotion.event.eventId != event.eventId) return ProgramPromotionCheck(false, NOT_CURRENT)
        if (promotion.event != event) throw ResponseStatusException(HttpStatus.CONFLICT, "승급 이벤트 식별자가 일치하지 않습니다.")
        if (promotion.status == "CANCELLED") return ProgramPromotionCheck(false, "CANCELLED")
        if (promotion.status != "CONFIRMED") return ProgramPromotionCheck(false, NOT_CURRENT)
        if (promotion.createdAt.isBefore(policy.cutoff())) return ProgramPromotionCheck(false, NOT_CURRENT)
        return null
    }

    private companion object {
        const val NOT_CURRENT = "NOT_CURRENT_PROMOTION"

        // Same rule as CheckPromotionOfferServiceImpl (#243): Notification labels are trimmed, at most max UTF-16 units
        // and free of \p{C}, \p{Zl} and \p{Zp}. Line breaks become spaces and invisible format characters are dropped.
        // ponytail: duplicated until #243 merges, then share one helper.
        val BREAKS = Regex("""[\p{Cc}\p{Zl}\p{Zp}]+""")
        val HIDDEN = Regex("""\p{C}""")

        fun label(
            value: String,
            max: Int,
        ): String? =
            value
                .replace(BREAKS, " ")
                .replace(HIDDEN, "")
                .trim()
                .takeIf { it.isNotEmpty() && it.length <= max }
    }
}
