package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.AdminCancellationResult
import team.startup.application.domain.application.presentation.dto.CancelAdminApplicationCommand
import team.startup.application.domain.application.service.CancelAdminApplicationService
import java.util.UUID

@RestController
class AdminApplicationCancellationController(
    private val cancellation: CancelAdminApplicationService,
) {
    @Operation(
        summary = "관리자 프로그램 신청 취소",
        description = "행사별 별도 취소 권한, 취소 마감, 입장·출석·스캔 보호를 확인합니다. 기존 자동 승급 정책을 따르며 강제 취소는 제공하지 않습니다. 승급 결과는 행사 처리 이력과 원장을 다시 조회합니다.",
    )
    @PostMapping("/application/admin/expos/{expoId}/program-session-applications/{applicationId}/cancellation")
    fun cancel(
        @PathVariable expoId: UUID,
        @PathVariable applicationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<AdminCancellationResult> =
        ResponseEntity
            .ok()
            .cacheControl(
                CacheControl.noStore(),
            ).body(cancellation.execute(CancelAdminApplicationCommand(expoId, applicationId, authorization)))
}
