package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.OwnerPromotionOffer
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOfferCommand

interface AcceptPromotionOfferService {
    fun execute(command: OwnerPromotionOfferCommand): OwnerPromotionOffer
}
