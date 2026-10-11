package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.ProgramPromotionCheck
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent
import team.startup.application.domain.application.service.CheckProgramPromotionService

@RestController
@SecurityRequirement(name = "internalToken")
class ProgramPromotionController(
    private val service: CheckProgramPromotionService,
) {
    @Operation(summary = "발송 직전 현재 확정 및 동일 승급 확인")
    @PostMapping("/internal/program-promotions/check")
    fun check(
        @Valid @RequestBody event: ProgramPromotionEvent,
    ): ProgramPromotionCheck = service.execute(event)
}
