package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationCancellationRequest
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationCancellationResult
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationDetailCommand
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationReadCommand
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargets
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargetsCommand
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargetsRequest
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelAdminPreregistrationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.CancelAdminPreregistrationService
import team.startup.application.domain.application.service.GetAdminPreregistrationDetailService
import team.startup.application.domain.application.service.GetAdminPreregistrationsService
import team.startup.application.domain.application.service.SelectAdminPreregistrationTargetsService
import java.util.UUID

@RestController
@RequestMapping("/application/admin/expos/{expoId}/preregistrations")
class AdminPreregistrationController(
    private val authorization: AuthorizeAdminApplicationService,
    private val list: GetAdminPreregistrationsService,
    private val detail: GetAdminPreregistrationDetailService,
    private val targets: SelectAdminPreregistrationTargetsService,
    private val cancellation: CancelAdminPreregistrationService,
) {
    @Operation(
        summary = "사전등록 목록·검색",
        description =
            "APPLICATION_READ 필요. 확정·취소는 참가자별, 대기·처리중·대기철회는 예약 일행별 행입니다. " +
                "q는 확정/취소의 User 현재 이름·번호, 예약의 제출 당시 대표자 이름·번호입니다. " +
                "sessionId, 쉼표 복수 status 지원. sessionId/key 오름차순. totalElements는 인원이 아닌 행 수입니다. " +
                "전체 인원 집계는 기존 /summary를 사용합니다. 상세 계약과 외부 의존성은 docs/admin-preregistration-ledger.md 참조.",
    )
    @GetMapping
    fun list(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminPreregistrationQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminPreregistrationPage> {
        authorize(expoId, request, setOf("sessionId", "status", "q", "page", "size"))
        return response(list.execute(AdminPreregistrationReadCommand(expoId, query)))
    }

    @Operation(summary = "사전등록 상세", description = "목록의 key를 그대로 전달합니다. 다른 행사·탭 또는 없는 key는 404입니다.")
    @GetMapping("/detail")
    fun detail(
        @PathVariable expoId: UUID,
        @RequestParam key: String,
        request: HttpServletRequest,
    ): ResponseEntity<AdminPreregistrationRow> {
        authorize(expoId, request, setOf("key"))
        if (key.length > 160) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid key")
        return response(detail.execute(AdminPreregistrationDetailCommand(expoId, key)))
    }

    @Operation(
        summary = "사전등록 선택·검색 전체 대상 해석",
        description = "목록과 동일 필터, 최대 5000행. 현재 행사/사전등록 탭만 허용. 실제 Report/Notification 실행은 공급자 확장 전 미지원입니다.",
    )
    @PostMapping("/targets")
    fun targets(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: AdminPreregistrationTargetsRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AdminPreregistrationTargets> {
        authorize(expoId, request, emptySet())
        return response(targets.execute(AdminPreregistrationTargetsCommand(expoId, body)))
    }

    @Operation(
        summary = "관리자 사전등록 취소·대기 철회",
        description =
            "APPLICATION_CANCEL 필요. 같은 회차·대표자 한 일행의 APPLICATION key(최대 5건, 대표자는 동행자와 함께) " +
                "또는 대기 RESERVATION key 하나를 받습니다. 입장·취소 마감·활성 프로그램 신청 보호와 대기 승급은 " +
                "기존 규칙을 따르며 실행자는 ADMIN과 관리자 ID로 기록합니다. 일행 혼합은 400, 없는·다른 행사 key는 404입니다.",
    )
    @PostMapping("/cancellation")
    fun cancel(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: AdminPreregistrationCancellationRequest,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<AdminPreregistrationCancellationResult> =
        response(cancellation.execute(CancelAdminPreregistrationCommand(expoId, body.keys, authorization)))

    private fun authorize(
        expoId: UUID,
        request: HttpServletRequest,
        allowed: Set<String>,
    ) {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, request.getHeader("Authorization")))
        if (request.parameterMap.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported, repeated or empty query parameter")
        }
    }

    private fun <T : Any> response(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}

// #267: 권한 거절 사유만 code로 싣는다. 다른 오류는 ResponseStatusExceptionResolver와 같이 sendError로 기본 오류 응답에 맡긴다.
@RestControllerAdvice(assignableTypes = [AdminPreregistrationController::class])
class AdminPreregistrationExceptionHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun expected(
        exception: ResponseStatusException,
        response: HttpServletResponse,
    ): ResponseEntity<ProblemDetail>? =
        adminAccessDenial(exception) ?: run {
            response.sendError(exception.statusCode.value(), exception.reason)
            null
        }
}
