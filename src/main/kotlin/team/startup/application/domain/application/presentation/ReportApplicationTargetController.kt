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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CreateReportApplicationSnapshotRequest
import team.startup.application.domain.application.presentation.dto.ReportApplicationSelection
import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import team.startup.application.domain.application.presentation.dto.SelectReportApplicationTargetsRequest
import team.startup.application.domain.application.service.CreateReportApplicationSnapshotService
import team.startup.application.domain.application.service.GetReportApplicationSnapshotService
import team.startup.application.domain.application.service.SelectReportApplicationTargetsService
import team.startup.application.domain.application.service.impl.AdminExpoGateway
import java.util.UUID

@RestController
@RequestMapping(
    "/internal/expos/{expoId}/program-session-applications/report-targets",
    "/application/admin/expos/{expoId}/program-session-applications/report-targets",
)
class ReportApplicationTargetController(
    private val selection: SelectReportApplicationTargetsService,
    private val createSnapshot: CreateReportApplicationSnapshotService,
    private val getSnapshot: GetReportApplicationSnapshotService,
    private val authority: AdminExpoGateway,
) {
    @Operation(summary = "UUID 신청 선택 조회", description = "내부 경로는 서비스 토큰, 관리자 경로는 원본 JWT와 행사 권한 검증. 누락/타 행사 ID는 전체 404.")
    @PostMapping("/selection")
    fun select(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: SelectReportApplicationTargetsRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ReportApplicationSelection> {
        requireAccess(expoId, request)
        checkParameters(request, emptySet())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(selection.execute(expoId, body))
    }

    @Operation(
        summary = "동일 필터 전체 대상 스냅샷 생성",
        description = "원장 조건과 User q/occupation 후보를 교차. 최대 10000 신청, 페이지 1–500, 15분 유지. 등록 답변/출석 원천은 참조만 제공합니다.",
    )
    @PostMapping
    fun create(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: CreateReportApplicationSnapshotRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ReportApplicationSnapshotPage> {
        requireAccess(expoId, request)
        checkParameters(request, emptySet())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(createSnapshot.execute(expoId, body))
    }

    @Operation(summary = "고정된 전체 대상의 다음 페이지", description = "반환된 nextCursor만 전달. 필터/정렬/페이지 상한은 생성 시 고정됩니다.")
    @GetMapping
    fun next(
        @PathVariable expoId: UUID,
        @RequestParam cursor: String,
        request: HttpServletRequest,
    ): ResponseEntity<ReportApplicationSnapshotPage> {
        requireAccess(expoId, request)
        checkParameters(request, setOf("cursor"))
        if (cursor.length > 128) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report cursor")
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(getSnapshot.execute(expoId, cursor))
    }

    private fun requireAccess(
        expoId: UUID,
        request: HttpServletRequest,
    ) {
        if (!request.requestURI.startsWith("${request.contextPath}/internal/")) {
            authority.requireAccess(expoId, request.getHeader("Authorization"))
        }
    }

    private fun checkParameters(
        request: HttpServletRequest,
        allowed: Set<String>,
    ) {
        if (request.parameterMap.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported, repeated or empty query parameter")
        }
    }
}
