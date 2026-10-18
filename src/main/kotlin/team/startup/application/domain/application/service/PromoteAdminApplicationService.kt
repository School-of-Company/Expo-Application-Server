package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPromotionResult
import team.startup.application.domain.application.presentation.dto.PromoteAdminApplicationCommand

interface PromoteAdminApplicationService {
    fun execute(command: PromoteAdminApplicationCommand): AdminPromotionResult
}
