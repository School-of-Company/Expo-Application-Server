package team.startup.application.domain.application.presentation

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

    private fun supported(): ResponseEntity<Void> = ResponseEntity.noContent().header("X-Program-Guardian-Policy-Version", "1").build()
}
