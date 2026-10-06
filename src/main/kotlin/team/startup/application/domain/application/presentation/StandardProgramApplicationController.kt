package team.startup.application.domain.application.presentation

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand
import team.startup.application.domain.application.presentation.dto.ParticipantReference
import team.startup.application.domain.application.presentation.dto.StandardApplicationResponse
import team.startup.application.domain.application.presentation.dto.StandardProgramReference
import team.startup.application.domain.application.service.StandardProgramApplicationService

data class StandardApplicationRequest(
    @field:Valid val participant: ParticipantReferenceRequest,
    @field:Valid val programs: List<StandardProgramReferenceRequest>,
)

data class ParticipantReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
)

data class StandardProgramReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
)

@RestController
@RequestMapping("/internal/standard-program-applications")
class StandardProgramApplicationController(
    private val service: StandardProgramApplicationService,
) {
    @PostMapping
    fun apply(
        @Valid @RequestBody request: StandardApplicationRequest,
    ): ResponseEntity<Void> {
        if (request.programs.any { it.expoId != request.participant.expoId }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "프로그램의 박람회가 참가자의 박람회와 다릅니다.")
        }
        val programs = request.programs.distinctBy { it.id }
        if (programs.isNotEmpty()) {
            service.execute(
                ApplyStandardProgramsCommand(
                    ParticipantReference(request.participant.id, request.participant.expoId),
                    programs.map { StandardProgramReference(it.id, it.expoId) },
                ),
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @GetMapping("/program/{programId}")
    fun list(
        @Positive @PathVariable programId: Long,
    ): List<StandardApplicationResponse> = service.list(programId)

    @DeleteMapping("/program/{programId}")
    fun delete(
        @Positive @PathVariable programId: Long,
    ): ResponseEntity<Void> {
        service.delete(programId)
        return ResponseEntity.noContent().build()
    }
}
