package team.startup.application.domain.application.presentation

import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.service.ProgramApplicationLookupService

data class ProgramApplicationLookupResponse(
    val applied: Boolean,
)

@RestController
@RequestMapping("/internal/program-applications")
class ProgramApplicationLookupController(
    private val service: ProgramApplicationLookupService,
) {
    @GetMapping("/standard/{programId}/participants/{participantId}")
    fun standard(
        @PathVariable @Positive programId: Long,
        @PathVariable @Positive participantId: Long,
    ) = ProgramApplicationLookupResponse(service.isStandardApplied(programId, participantId))

    @GetMapping("/training/{programId}/trainees/{traineeId}")
    fun training(
        @PathVariable @Positive programId: Long,
        @PathVariable @Positive traineeId: Long,
    ) = ProgramApplicationLookupResponse(service.isTrainingApplied(programId, traineeId))
}
