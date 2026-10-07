package team.startup.application.domain.application.presentation

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.entity.TrainingProgramCategory
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.TraineeReference
import team.startup.application.domain.application.presentation.dto.TrainingApplicationResponse
import team.startup.application.domain.application.presentation.dto.TrainingProgramReference
import team.startup.application.domain.application.service.ApplyTrainingProgramsService
import team.startup.application.domain.application.service.DeleteTrainingProgramApplicationsService
import team.startup.application.domain.application.service.GetTrainingProgramApplicationsService
import team.startup.application.domain.application.service.ReplaceTrainingProgramsService

data class TrainingApplicationRequest(
    @field:Valid val trainee: TraineeReferenceRequest,
    @field:Valid @field:NotEmpty val programs: List<TrainingProgramReferenceRequest>,
)

data class TrainingReplacementRequest(
    @field:Valid val trainee: TraineeReferenceRequest,
    @field:Valid val programs: List<TrainingProgramReferenceRequest>,
)

data class TraineeReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
)

data class TrainingProgramReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
    val category: TrainingProgramCategory,
)

// X-Internal-Token 검증은 InternalTokenFilter가 /internal/** 전체에 적용한다.
@RestController
@RequestMapping("/internal/training-program-applications")
class TrainingProgramApplicationController(
    private val applyService: ApplyTrainingProgramsService,
    private val replaceService: ReplaceTrainingProgramsService,
    private val getService: GetTrainingProgramApplicationsService,
    private val deleteService: DeleteTrainingProgramApplicationsService,
) {
    @PostMapping
    fun apply(
        @Valid @RequestBody request: TrainingApplicationRequest,
    ): ResponseEntity<Void> {
        applyService.execute(
            ApplyTrainingProgramsCommand(
                TraineeReference(request.trainee.id, request.trainee.expoId),
                request.programs.map { TrainingProgramReference(it.id, it.expoId, it.category) },
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @PutMapping("/trainee/{traineeId}")
    fun replace(
        @PathVariable @Positive traineeId: Long,
        @Valid @RequestBody request: TrainingReplacementRequest,
    ): ResponseEntity<Void> {
        require(traineeId == request.trainee.id)
        replaceService.execute(
            ApplyTrainingProgramsCommand(
                TraineeReference(request.trainee.id, request.trainee.expoId),
                request.programs.map { TrainingProgramReference(it.id, it.expoId, it.category) },
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/program/{programId}")
    fun list(
        @PathVariable @Positive programId: Long,
    ): List<TrainingApplicationResponse> = getService.execute(programId)

    @DeleteMapping("/program/{programId}")
    fun delete(
        @PathVariable @Positive programId: Long,
    ): ResponseEntity<Void> {
        deleteService.execute(programId)
        return ResponseEntity.noContent().build()
    }

    // 서비스의 require 위반(행사 ID 불일치, 요청 내 중복 프로그램 ID)은 500이 아니라 400으로 응답한다.
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(): ResponseEntity<Void> = ResponseEntity.badRequest().build()
}
