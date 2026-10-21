package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand
import team.startup.application.domain.application.presentation.dto.ProgramChangePayload
import team.startup.application.domain.application.service.PrepareProgramChangeService
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
class ProgramGuardianPolicyController(
    private val prepare: PrepareProgramChangeService,
) {
    @GetMapping("/internal/expos/{expoId}/program-policy-capabilities")
    fun capabilities(
        @PathVariable expoId: UUID,
    ): ResponseEntity<Void> = supported()

    @PutMapping("/internal/expos/{expoId}/program-changes/{changeId}/guardian-policy")
    fun prepare(
        @PathVariable expoId: UUID,
        @PathVariable changeId: UUID,
        @Valid @RequestBody payload: ProgramChangePayload,
    ): ResponseEntity<Void> {
        prepare.execute(PrepareProgramChangeCommand(expoId, changeId, payload))
        return supported()
    }

    private fun supported(): ResponseEntity<Void> =
        ResponseEntity
            .noContent()
            .header("X-Program-Guardian-Policy-Version", "1")
            // Expo #189는 guardianSchoolValue를 이 신호가 있을 때만 보낸다. 구버전은 모르는 필드를 무시하고 승인한다.
            .header("X-Program-Guardian-School-Value-Version", "1")
            .header(PROMOTION_POLICY_HEADER, "1")
            .build()
}

// Expo #143 saves auto-promotion off/end settings only after this signal; older Applications would ignore them (#172).
internal const val PROMOTION_POLICY_HEADER = "X-Program-Promotion-Policy-Version"
