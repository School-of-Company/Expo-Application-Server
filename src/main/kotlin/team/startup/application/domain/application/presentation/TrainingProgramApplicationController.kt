package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
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
import team.startup.application.domain.application.presentation.dto.TrainingApplicationVersionResponse
import team.startup.application.domain.application.presentation.dto.TrainingOperationReceipt
import team.startup.application.domain.application.presentation.dto.TrainingProgramReference
import team.startup.application.domain.application.service.ApplyTrainingProgramsService
import team.startup.application.domain.application.service.DeleteTrainingProgramApplicationsService
import team.startup.application.domain.application.service.GetTrainingApplicationVersionService
import team.startup.application.domain.application.service.GetTrainingOperationReceiptService
import team.startup.application.domain.application.service.GetTrainingProgramApplicationsService
import team.startup.application.domain.application.service.ReplaceTrainingProgramsService
import java.util.UUID

@Schema(description = "연수 프로그램 추가 신청 요청")
data class TrainingApplicationRequest(
    @field:Valid val trainee: TraineeReferenceRequest,
    @field:Valid @field:NotEmpty val programs: List<TrainingProgramReferenceRequest>,
    val operationId: UUID? = null,
    @field:PositiveOrZero val expectedVersion: Long? = null,
)

@Schema(description = "연수 프로그램 전체 교체 요청. 빈 프로그램 목록은 전체 취소")
data class TrainingReplacementRequest(
    @field:Valid val trainee: TraineeReferenceRequest,
    @field:Valid val programs: List<TrainingProgramReferenceRequest>,
    val operationId: UUID? = null,
    @field:PositiveOrZero val expectedVersion: Long? = null,
)

@Schema(description = "연수자와 소속 박람회", example = """{"id":456,"expoId":"0199c000-0000-7000-8000-000000000001"}""")
data class TraineeReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
)

@Schema(description = "연수 프로그램과 분류")
data class TrainingProgramReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
    val category: TrainingProgramCategory,
)

// X-Internal-Token 검증은 InternalTokenFilter가 /internal/** 전체에 적용한다.
@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
@RequestMapping("/internal/training-program-applications")
class TrainingProgramApplicationController(
    private val applyService: ApplyTrainingProgramsService,
    private val replaceService: ReplaceTrainingProgramsService,
    private val getService: GetTrainingProgramApplicationsService,
    private val deleteService: DeleteTrainingProgramApplicationsService,
    private val receiptService: GetTrainingOperationReceiptService,
    private val versionService: GetTrainingApplicationVersionService,
) {
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "신청 완료"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping
    fun apply(
        @Valid @RequestBody request: TrainingApplicationRequest,
    ): ResponseEntity<Void> {
        applyService.execute(
            ApplyTrainingProgramsCommand(
                TraineeReference(request.trainee.id, request.trainee.expoId),
                request.programs.map { TrainingProgramReference(it.id, it.expoId, it.category) },
                request.operationId,
                request.expectedVersion,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
        ],
    )
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
                request.operationId,
                request.expectedVersion,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @ApiResponse(responseCode = "200", description = "조회 결과")
    @GetMapping("/program/{programId}")
    fun list(
        @PathVariable @Positive programId: Long,
    ): List<TrainingApplicationResponse> = getService.execute(programId)

    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 또는 처리 결과"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
        ],
    )
    @GetMapping("/operations/{operationId}")
    fun receipt(
        @PathVariable operationId: UUID,
    ): ResponseEntity<TrainingOperationReceipt> =
        receiptService.execute(operationId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @ApiResponse(responseCode = "200", description = "조회 결과")
    @GetMapping("/trainee/{traineeId}/version")
    fun version(
        @PathVariable @Positive traineeId: Long,
    ): TrainingApplicationVersionResponse = versionService.execute(traineeId)

    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
        ],
    )
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
