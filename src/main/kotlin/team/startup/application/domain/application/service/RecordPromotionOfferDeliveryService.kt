package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryCommand
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryResult

interface RecordPromotionOfferDeliveryService {
    fun execute(command: PromotionOfferDeliveryCommand): PromotionOfferDeliveryResult
}
