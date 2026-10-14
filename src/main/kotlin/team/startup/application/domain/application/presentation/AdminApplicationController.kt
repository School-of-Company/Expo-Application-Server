package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationReadCommand
import team.startup.application.domain.application.presentation.dto.AdminApplicationSummary
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionPage
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionQuery
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminApplicationSummaryService
import team.startup.application.domain.application.service.GetAdminApplicationsService
import team.startup.application.domain.application.service.GetAdminCurrentSessionsService
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "내부 경로의 X-Internal-Token 또는 관리자 경로의 Bearer JWT 인증 실패"),
        ApiResponse(responseCode = "403", description = "관리자의 행사 APPLICATION_READ 권한 거부"),
    ],
)
@RequestMapping("/internal/expos/{expoId}/program-session-applications", "/application/admin/expos/{expoId}/program-session-applications")
class AdminApplicationController(
    private val service: GetAdminApplicationsService,
    private val summaryService: GetAdminApplicationSummaryService,
    private val currentSessions: GetAdminCurrentSessionsService,
    private val authorization: AuthorizeAdminApplicationService,
) {
    @Operation(
        summary = "내부 프로그램 회차 접수 원장 조회",
        description =
            "내부 경로는 내부 토큰, 관리자 경로는 원본 Bearer JWT의 Expo 현재 APPLICATION_READ 검증이 필요합니다. " +
                "search는 제출 당시 제목, q는 User 이름/번호, occupation은 원본 직업 코드입니다. from 포함/until 제외(신청 시각). " +
                "status는 쉼표로 여러 개, eventDate는 제출 당시 회차 시작일(KST)입니다. receiptNumber는 박람회별 접수번호입니다. " +
                "동일 필터의 목록/전체 건수/상태 집계 및 applicationId 상세/이력을 반환합니다. participant.information은 User 현재 등록 답변입니다. " +
                "관리자 목록은 User 참가자가 하나라도 누락되면 전체 502로 실패하며 원장 스냅샷은 보존합니다. participant=null 성공으로 대체하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 또는 처리 결과"),
            ApiResponse(responseCode = "502", description = "User 참가자 누락 또는 공급자 응답 계약 오류"),
        ],
    )
    @GetMapping
    fun list(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminApplicationQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminApplicationPage> {
        authorize(expoId, request)
        validateParameters(request)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.execute(AdminApplicationReadCommand(expoId, query, isAdminRequest(request))))
    }

    @Operation(
        summary = "내부 프로그램 회차 접수 집계",
        description =
            "목록과 같은 인증 및 필터의 신청 건수/고유 참가자/회차별 집계. " +
                "attendance는 표시된 회차 전체의 실제 출석 인원과 HH:mm 입실 범위이며 접수 필터를 적용하지 않습니다. " +
                "행사 입장·사전등록·현재 정원은 포함하지 않으며 출석 원천 장애는 오류로 반환합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 또는 처리 결과"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
        ],
    )
    @GetMapping("/summary")
    fun summary(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminApplicationQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminApplicationSummary> {
        authorize(expoId, request)
        validateParameters(request)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaryService.execute(expoId, query))
    }

    @Operation(
        summary = "현재 모집 회차 조회",
        description = "접수가 없는 현재 회차도 포함합니다. 후속 페이지는 definitionVersion을 유지하며 변경 시 409입니다. 삭제된 과거 회차는 원장 이력으로 조회합니다.",
    )
    @GetMapping("/current-sessions")
    fun sessions(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminCurrentSessionQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCurrentSessionPage> {
        authorize(expoId, request)
        validateParameters(request, setOf("programType", "programId", "groupId", "page", "size", "definitionVersion"))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(currentSessions.execute(expoId, query))
    }

    private fun authorize(
        expoId: UUID,
        request: HttpServletRequest,
    ) {
        if (isAdminRequest(request)) {
            authorization.execute(AuthorizeAdminApplicationCommand(expoId, request.getHeader("Authorization")))
        }
    }

    private fun isAdminRequest(request: HttpServletRequest) = !request.requestURI.startsWith("${request.contextPath}/internal/")

    private fun validateParameters(
        request: HttpServletRequest,
        allowed: Set<String> =
            setOf(
                "programType",
                "programId",
                "groupId",
                "applicationId",
                "status",
                "search",
                "q",
                "occupation",
                "eventDate",
                "from",
                "until",
                "page",
                "size",
                "sort",
            ),
    ) {
        if (request.parameterMap.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported, repeated or empty query parameter")
        }
    }
}
