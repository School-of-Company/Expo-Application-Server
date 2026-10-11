package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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
import team.startup.application.domain.application.service.ApplyStandardProgramsService
import team.startup.application.domain.application.service.DeleteStandardProgramApplicationsService
import team.startup.application.domain.application.service.GetStandardProgramApplicationsService

@Schema(
    description = "일반 프로그램 신청 요청",
    example = """{
        "participant":{"id":123,"expoId":"0199c000-0000-7000-8000-000000000001"},
        "programs":[{"id":1,"expoId":"0199c000-0000-7000-8000-000000000001"}]
    }""",
)
data class StandardApplicationRequest(
    @field:Valid val participant: ParticipantReferenceRequest,
    @field:Valid val programs: List<StandardProgramReferenceRequest>,
)

@Schema(description = "참가자와 소속 박람회", example = """{"id":123,"expoId":"0199c000-0000-7000-8000-000000000001"}""")
data class ParticipantReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
)

@Schema(description = "일반 프로그램과 소속 박람회", example = """{"id":1,"expoId":"0199c000-0000-7000-8000-000000000001"}""")
data class StandardProgramReferenceRequest(
    @field:Positive val id: Long,
    @field:NotBlank val expoId: String,
)

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
@RequestMapping("/internal/standard-program-applications")
class StandardProgramApplicationController(
    private val applyService: ApplyStandardProgramsService,
    private val getService: GetStandardProgramApplicationsService,
    private val deleteService: DeleteStandardProgramApplicationsService,
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
        @Valid @RequestBody request: StandardApplicationRequest,
    ): ResponseEntity<Void> {
        if (request.programs.any { it.expoId != request.participant.expoId }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "프로그램의 박람회가 참가자의 박람회와 다릅니다.")
        }
        val programs = request.programs.distinctBy { it.id }
        if (programs.isNotEmpty()) {
            applyService.execute(
                ApplyStandardProgramsCommand(
                    ParticipantReference(request.participant.id, request.participant.expoId),
                    programs.map { StandardProgramReference(it.id, it.expoId) },
                ),
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @ApiResponse(responseCode = "200", description = "조회 결과")
    @GetMapping("/program/{programId}")
    fun list(
        @Positive @PathVariable programId: Long,
    ): List<StandardApplicationResponse> = getService.execute(programId)

    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
        ],
    )
    @DeleteMapping("/program/{programId}")
    fun delete(
        @Positive @PathVariable programId: Long,
    ): ResponseEntity<Void> {
        deleteService.execute(programId)
        return ResponseEntity.noContent().build()
    }
}
