package team.startup.application.domain.application.service.impl

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

@Service
class CheckProgramPromotionServiceImpl(
    private val repository: ProgramPromotionRepository,
    private val gateway: ProgramPromotionGateway,
    private val policy: ProgramPromotionPolicy,
    private val clock: Clock,
) : CheckProgramPromotionService {
    @Transactional(propagation = Propagation.NEVER)
    override fun execute(event: ProgramPromotionEvent): ProgramPromotionCheck {
        ineligible(event)?.let { return it }
        val details = gateway.details(event)
        ineligible(event)?.let { return it }
        if (!clock.instant().isBefore(OffsetDateTime.parse(requireNotNull(details.startedAt)).toInstant())) {
            return ProgramPromotionCheck(false, "NOT_CURRENT_PROMOTION")
        }
        return details
    }

    private fun ineligible(event: ProgramPromotionEvent): ProgramPromotionCheck? {
        val promotion =
            repository.find(event.applicationId)
                ?: return ProgramPromotionCheck(false, "NOT_CURRENT_PROMOTION")
        if (promotion.event.eventId != event.eventId) return ProgramPromotionCheck(false, "NOT_CURRENT_PROMOTION")
        if (promotion.event != event) throw ResponseStatusException(HttpStatus.CONFLICT, "승급 이벤트 식별자가 일치하지 않습니다.")
        if (promotion.status == "CANCELLED") return ProgramPromotionCheck(false, "CANCELLED")
        if (promotion.status != "CONFIRMED") return ProgramPromotionCheck(false, "NOT_CURRENT_PROMOTION")
        if (promotion.createdAt.isBefore(policy.cutoff())) return ProgramPromotionCheck(false, "NOT_CURRENT_PROMOTION")
        return null
    }
}
