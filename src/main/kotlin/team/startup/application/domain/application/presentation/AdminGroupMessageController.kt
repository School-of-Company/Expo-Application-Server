package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageConfig
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageJob
import team.startup.application.domain.application.presentation.dto.AdminGroupMessagePreview
import team.startup.application.domain.application.presentation.dto.CreateAdminGroupMessagePreviewRequest
import team.startup.application.domain.application.presentation.dto.ExecuteAdminGroupMessageRequest
import team.startup.application.domain.application.service.CreateAdminGroupMessagePreviewService
import team.startup.application.domain.application.service.ExecuteAdminGroupMessageService
import team.startup.application.domain.application.service.GetAdminGroupMessageConfigService
import team.startup.application.domain.application.service.GetAdminGroupMessageJobService
import team.startup.application.domain.application.service.RetryAdminGroupMessageService
import java.util.UUID

@RestController
@RequestMapping("/application/expos/{expoId}/admin-group-messages")
class AdminGroupMessageController(
    private val config: GetAdminGroupMessageConfigService,
    private val preview: CreateAdminGroupMessagePreviewService,
    private val execute: ExecuteAdminGroupMessageService,
    private val status: GetAdminGroupMessageJobService,
    private val retry: RetryAdminGroupMessageService,
) {
    @Operation(summary = "관리자 그룹 발송 채널 설정", description = "현재 관리 권한을 확인하고 SMS 및 승인된 카카오 템플릿 사용 가능 여부를 반환합니다.")
    @GetMapping("/config")
    fun config(
        @PathVariable expoId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        request: HttpServletRequest,
    ): ResponseEntity<AdminGroupMessageConfig> {
        noParameters(request)
        return ok(config.execute(expoId, authorization))
    }

    @Operation(
        summary = "관리자 그룹 발송 미리보기",
        description =
            "현재 전역 관리자 권한과 프로그램 applicationIds/filter 또는 사전등록 keys/preregistrationFilter를 확인합니다. " +
                "혼합은 400, 행사 밖/없는 선택 key는 404, 5000행 초과는 422입니다. 사전등록은 Notification의 PREREGISTRATION_GROUP_MESSAGE_V1 지원이 필요합니다.",
    )
    @PostMapping("/previews")
    fun preview(
        @PathVariable expoId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody body: CreateAdminGroupMessagePreviewRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AdminGroupMessagePreview> {
        noParameters(request)
        return ResponseEntity
            .status(
                HttpStatus.CREATED,
            ).cacheControl(CacheControl.noStore())
            .body(preview.execute(expoId, authorization, body))
    }

    @Operation(summary = "미리보기 대상 발송 실행", description = "관리자 권한과 전체 대상을 다시 계산하고 fingerprint가 같을 때만 멱등 작업을 시작합니다.")
    @PostMapping("/previews/{previewId}/jobs")
    fun execute(
        @PathVariable expoId: UUID,
        @PathVariable previewId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody body: ExecuteAdminGroupMessageRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AdminGroupMessageJob> {
        noParameters(request)
        return accepted(execute.execute(expoId, previewId, authorization, body))
    }

    @Operation(summary = "관리자 그룹 발송 작업 조회", description = "현재 관리자 권한을 다시 확인하고 진행, 성공, 실패, 결과 불명 상태를 반환합니다.")
    @GetMapping("/jobs/{jobId}")
    fun status(
        @PathVariable expoId: UUID,
        @PathVariable jobId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        request: HttpServletRequest,
    ): ResponseEntity<AdminGroupMessageJob> {
        noParameters(request)
        validJobId(jobId)
        return ok(status.execute(expoId, jobId, authorization))
    }

    @Operation(summary = "확정 실패 대상 재시도", description = "관리자 권한과 전체 대상을 다시 확인하고 fingerprint가 같을 때만 확정 실패 대상 재시도를 요청합니다.")
    @PostMapping("/jobs/{jobId}/retries")
    fun retry(
        @PathVariable expoId: UUID,
        @PathVariable jobId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody body: ExecuteAdminGroupMessageRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AdminGroupMessageJob> {
        noParameters(request)
        validJobId(jobId)
        return accepted(retry.execute(expoId, jobId, authorization, body))
    }

    private fun noParameters(request: HttpServletRequest) {
        if (request.parameterMap.isNotEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Query parameters are not supported")
    }

    private fun validJobId(jobId: String) {
        if (!jobId.matches(Regex("[a-f0-9]{64}"))) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid jobId")
    }

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun accepted(body: AdminGroupMessageJob): ResponseEntity<AdminGroupMessageJob> =
        ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(body)
}
