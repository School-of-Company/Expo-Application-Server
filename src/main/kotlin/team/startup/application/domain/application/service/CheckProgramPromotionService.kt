package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ProgramPromotionCheck
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent

interface CheckProgramPromotionService {
    fun execute(event: ProgramPromotionEvent): ProgramPromotionCheck
}
