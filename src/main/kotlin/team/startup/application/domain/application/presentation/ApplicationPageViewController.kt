package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.AdminApplicationPageViews
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.RecordApplicationPageViewCommand
import team.startup.application.domain.application.presentation.dto.RecordApplicationPageViewRequest
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminApplicationPageViewsService
import team.startup.application.domain.application.service.RecordApplicationPageViewService
import java.util.UUID

@RestController
class ApplicationPageViewController(
    private val record: RecordApplicationPageViewService,
    private val authorization: AuthorizeAdminApplicationService,
    private val pageViews: GetAdminApplicationPageViewsService,
) {
    @Operation(
        summary = "신청화면 진입 기록",
        description = "로그인 없이 신청화면 진입마다 한 번 호출합니다. 새로고침도 PV에 포함하며 UV는 같은 visitorId를 Asia/Seoul 하루 한 번 셉니다.",
        responses = [
            ApiResponse(responseCode = "204", description = "기록됨"),
            ApiResponse(responseCode = "400", description = "잘못된 박람회 ID 또는 visitorId"),
        ],
    )
    @PostMapping("/application/expos/{expoId}/page-views")
    fun record(
        @PathVariable expoId: UUID,
        @Valid @RequestBody request: RecordApplicationPageViewRequest,
    ): ResponseEntity<Void> {
        record.execute(RecordApplicationPageViewCommand(expoId, requireNotNull(request.visitorId)))
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "관리자 신청화면 PV·UV 조회",
        description = "오늘 PV·UV, 누적 PV, 일별 PV·UV를 반환합니다. 날짜는 Asia/Seoul 기준입니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "신청화면 조회 집계"),
            ApiResponse(responseCode = "401", description = "관리자 JWT 인증 실패"),
            ApiResponse(responseCode = "403", description = "행사 APPLICATION_READ 권한 없음"),
            ApiResponse(responseCode = "502", description = "Expo 권한 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가"),
        ],
    )
    @GetMapping("/application/admin/expos/{expoId}/page-views")
    fun get(
        @PathVariable expoId: UUID,
        @RequestHeader("Authorization", required = false) bearer: String?,
    ): ResponseEntity<AdminApplicationPageViews> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, bearer))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pageViews.execute(expoId))
    }
}
