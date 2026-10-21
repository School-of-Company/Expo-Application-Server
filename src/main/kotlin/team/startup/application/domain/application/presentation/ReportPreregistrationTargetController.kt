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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CreateReportPreregistrationSnapshotRequest
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSelection
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSnapshotPage
import team.startup.application.domain.application.presentation.dto.SelectReportPreregistrationTargetsRequest
import team.startup.application.domain.application.service.CreateReportPreregistrationSnapshotService
import team.startup.application.domain.application.service.GetReportPreregistrationSnapshotService
import team.startup.application.domain.application.service.SelectReportPreregistrationTargetsService
import team.startup.application.domain.application.service.impl.AdminExpoGateway
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 또는 관리자 인증 실패"),
    ],
)
@RequestMapping(
    "/internal/expos/{expoId}/preregistrations/report-targets",
    "/application/admin/expos/{expoId}/preregistrations/report-targets",
)
class ReportPreregistrationTargetController(
    private val selection: SelectReportPreregistrationTargetsService,
    private val createSnapshot: CreateReportPreregistrationSnapshotService,
    private val getSnapshot: GetReportPreregistrationSnapshotService,
    private val authority: AdminExpoGateway,
) {
    @Operation(
        summary = "사전등록 접수대장 key 선택 조회",
        description = "내부 경로는 서비스 토큰, 관리자 경로는 원본 JWT와 행사 권한 검증. 없는·타 행사·프로그램 key가 하나라도 있으면 전체 404.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 결과"),
            ApiResponse(responseCode = "404", description = "요청 대상 없음"),
            ApiResponse(responseCode = "409", description = "삭제된 행사"),
            ApiResponse(responseCode = "502", description = "User 참가자 누락 또는 잘못된 응답"),
            ApiResponse(responseCode = "503", description = "원천 조회 불가"),
        ],
    )
    @PostMapping("/selection")
    fun select(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: SelectReportPreregistrationTargetsRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ReportPreregistrationSelection> {
        requireAccess(expoId, request, emptySet())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(selection.execute(expoId, body))
    }

    @Operation(
        summary = "사전등록 동일 필터 전체 대상 스냅샷 생성",
        description =
            "접수대장 sessionId/status/q와 같은 의미. 최대 5000행, 페이지 1–500, 15분 유지. " +
                "참가자×회차 APPLICATION 행과 User 미저장 예약 일행 RESERVATION 행을 구분합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "첫 페이지"),
            ApiResponse(responseCode = "409", description = "삭제된 행사"),
            ApiResponse(responseCode = "422", description = "대상 5000행 상한 초과"),
            ApiResponse(responseCode = "502", description = "User 참가자 누락 또는 잘못된 응답"),
            ApiResponse(responseCode = "503", description = "원천 조회 불가"),
        ],
    )
    @PostMapping
    fun create(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: CreateReportPreregistrationSnapshotRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ReportPreregistrationSnapshotPage> {
        requireAccess(expoId, request, emptySet())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(createSnapshot.execute(expoId, body))
    }

    @Operation(summary = "고정된 사전등록 대상의 다음 페이지", description = "반환된 nextCursor만 전달. 프로그램 보고 커서는 404입니다.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "다음 페이지"),
            ApiResponse(responseCode = "404", description = "스냅샷 없음 또는 다른 행사·원천"),
            ApiResponse(responseCode = "410", description = "스냅샷 만료"),
        ],
    )
    @GetMapping
    fun next(
        @PathVariable expoId: UUID,
        @RequestParam cursor: String,
        request: HttpServletRequest,
    ): ResponseEntity<ReportPreregistrationSnapshotPage> {
        requireAccess(expoId, request, setOf("cursor"))
        if (cursor.length > 128) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report cursor")
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(getSnapshot.execute(expoId, cursor))
    }

    private fun requireAccess(
        expoId: UUID,
        request: HttpServletRequest,
        allowed: Set<String>,
    ) {
        if (!request.requestURI.startsWith("${request.contextPath}/internal/")) {
            authority.requireAccess(expoId, request.getHeader("Authorization"))
        }
        if (request.parameterMap.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported, repeated or empty query parameter")
        }
    }
}
