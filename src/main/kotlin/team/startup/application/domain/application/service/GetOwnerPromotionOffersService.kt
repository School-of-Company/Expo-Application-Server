package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.OwnerPromotionOffer
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOfferCommand

interface GetOwnerPromotionOffersService {
    fun execute(command: OwnerPromotionOfferCommand): List<OwnerPromotionOffer>
}
