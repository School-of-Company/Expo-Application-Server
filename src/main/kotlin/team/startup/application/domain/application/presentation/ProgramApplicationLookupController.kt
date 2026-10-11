package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.service.CheckStandardProgramApplicationService
import team.startup.application.domain.application.service.CheckTrainingProgramApplicationService

@Schema(description = "프로그램 신청 여부", example = """{"applied":true}""")
data class ProgramApplicationLookupResponse(
    val applied: Boolean,
)

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
@RequestMapping("/internal/program-applications")
class ProgramApplicationLookupController(
    private val checkStandardService: CheckStandardProgramApplicationService,
    private val checkTrainingService: CheckTrainingProgramApplicationService,
) {
    @ApiResponse(responseCode = "200", description = "조회 결과")
    @GetMapping("/standard/{programId}/participants/{participantId}")
    fun standard(
        @PathVariable @Positive programId: Long,
        @PathVariable @Positive participantId: Long,
    ) = ProgramApplicationLookupResponse(checkStandardService.execute(programId, participantId))

    @ApiResponse(responseCode = "200", description = "조회 결과")
    @GetMapping("/training/{programId}/trainees/{traineeId}")
    fun training(
        @PathVariable @Positive programId: Long,
        @PathVariable @Positive traineeId: Long,
    ) = ProgramApplicationLookupResponse(checkTrainingService.execute(programId, traineeId))
}
