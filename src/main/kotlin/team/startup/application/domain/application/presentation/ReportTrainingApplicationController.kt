package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.TrainingApplicationForReportResponse
import team.startup.application.domain.application.presentation.dto.TrainingApplicationsByTraineesRequest
import team.startup.application.domain.application.service.ReportTrainingApplicationService

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
@RequestMapping("/internal/training-program-applications")
class ReportTrainingApplicationController(
    private val service: ReportTrainingApplicationService,
) {
    @ApiResponse(responseCode = "200", description = "조회 결과")
    @PostMapping("/trainees")
    fun listByTrainees(
        @Valid @RequestBody request: TrainingApplicationsByTraineesRequest,
    ): List<TrainingApplicationForReportResponse> = service.execute(request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(): ResponseEntity<Void> = ResponseEntity.badRequest().build()
}
