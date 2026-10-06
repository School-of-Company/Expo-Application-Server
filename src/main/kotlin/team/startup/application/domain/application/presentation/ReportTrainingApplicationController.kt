package team.startup.application.domain.application.presentation

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
@RequestMapping("/internal/training-program-applications")
class ReportTrainingApplicationController(
    private val service: ReportTrainingApplicationService,
) {
    @PostMapping("/trainees")
    fun listByTrainees(
        @Valid @RequestBody request: TrainingApplicationsByTraineesRequest,
    ): List<TrainingApplicationForReportResponse> = service.findAllByTrainees(request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(): ResponseEntity<Void> = ResponseEntity.badRequest().build()
}
