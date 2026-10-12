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
                "대기는 현행 정책상 0명이고, User 확정 전 예약의 건수·신청 인원은 별도 필드입니다. 프로그램 신청·출석 인원은 포함하지 않습니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "행사 및 회차별 사전등록 집계"),
            ApiResponse(responseCode = "401", description = "관리자 JWT 인증 실패"),
            ApiResponse(responseCode = "403", description = "행사 APPLICATION_READ 권한 없음"),
            ApiResponse(responseCode = "409", description = "삭제된 행사"),
            ApiResponse(responseCode = "502", description = "Expo 권한 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가"),
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
}
