package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSummary
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminPreregisterSummaryService
import java.util.UUID

@RestController
class AdminPreregisterSummaryController(
    private val authorization: AuthorizeAdminApplicationService,
    private val summary: GetAdminPreregisterSummaryService,
) {
    @Operation(
        summary = "관리자 사전등록 고유 인원 집계",
        description =
            "대표자와 동행자를 각각 한 명으로 셉니다. 현재 확정 참가자만 고유 인원이며 취소 이력과 중복될 수 있습니다. " +
                "대기는 User 저장 전 일행 인원 합이고, User 확정 전 예약의 건수·신청 인원은 별도 필드입니다. 프로그램 신청·출석 인원은 포함하지 않습니다. " +
                "직업별 확정 인원은 User 참가자 직업, 직업별 대기 인원은 신청 스냅샷 직업입니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "행사 및 회차별 사전등록 집계"),
            ApiResponse(responseCode = "401", description = "관리자 JWT 인증 실패"),
            ApiResponse(responseCode = "403", description = "행사 APPLICATION_READ 권한 없음"),
            ApiResponse(responseCode = "409", description = "삭제된 행사"),
            ApiResponse(responseCode = "502", description = "Expo 권한 또는 User 참가자 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 또는 User 연동 불가"),
        ],
    )
    @GetMapping("/application/admin/expos/{expoId}/preregistrations/summary")
    fun get(
        @PathVariable expoId: UUID,
        @RequestHeader("Authorization", required = false) bearer: String?,
    ): ResponseEntity<AdminPreregisterSummary> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, bearer))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summary.execute(expoId))
    }

    @Operation(
        summary = "내부 사전등록 고유 인원 집계",
        description = "관리자 경로와 같은 집계를 X-Internal-Token으로 조회합니다(Notification 등록 인원 리포트). 원장에 없는 행사는 0명입니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "행사 및 회차별 사전등록 집계"),
            ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
            ApiResponse(responseCode = "409", description = "삭제된 행사"),
            ApiResponse(responseCode = "502", description = "User 참가자 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    @GetMapping("/internal/expos/{expoId}/preregistrations/summary")
    fun getInternal(
        @PathVariable expoId: UUID,
    ): ResponseEntity<AdminPreregisterSummary> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summary.execute(expoId))
}
