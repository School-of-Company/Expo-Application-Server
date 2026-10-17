package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PromotionOfferCheck
import team.startup.application.domain.application.presentation.dto.PromotionOfferEvent

interface CheckPromotionOfferService {
    fun execute(event: PromotionOfferEvent): PromotionOfferCheck
}
