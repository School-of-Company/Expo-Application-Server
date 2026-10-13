package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.Positive
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlistCommand
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.WithdrawAdminPreregisterReservationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminPreregisterWaitlistService
import team.startup.application.domain.application.service.WithdrawAdminPreregisterReservationService
import java.util.UUID

@RestController
class AdminPreregisterWaitlistController(
    private val authorization: AuthorizeAdminApplicationService,
    private val waitlist: GetAdminPreregisterWaitlistService,
    private val withdrawal: WithdrawAdminPreregisterReservationService,
) {
    @Operation(
        summary = "관리자 사전등록 회차 대기 일행 목록",
        description =
            "WAITING 일행과 승급 뒤 User 저장 중인 PROCESSING 일행을 접수 순서대로 반환합니다. " +
                "대표자 이름·전화번호만 포함하고 동행자와 폼 답변은 포함하지 않습니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "회차 대기 일행 목록"),
            ApiResponse(responseCode = "400", description = "잘못된 박람회 또는 회차 ID"),
            ApiResponse(responseCode = "401", description = "관리자 JWT 인증 실패"),
            ApiResponse(responseCode = "403", description = "행사 APPLICATION_READ 권한 없음"),
            ApiResponse(responseCode = "409", description = "삭제된 행사"),
            ApiResponse(responseCode = "502", description = "Expo 권한 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가"),
        ],
    )
    @GetMapping("/application/admin/expos/{expoId}/preregister-sessions/{sessionId}/waitlist")
    fun get(
        @PathVariable expoId: UUID,
        @PathVariable @Positive sessionId: Long,
        @RequestHeader("Authorization", required = false) bearer: String?,
    ): ResponseEntity<AdminPreregisterWaitlist> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, bearer))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(waitlist.execute(AdminPreregisterWaitlistCommand(expoId, sessionId)))
    }

    @Operation(
        summary = "관리자 사전등록 대기 일행 전체 취소",
        description = "행사 APPLICATION_CANCEL 권한을 확인한 뒤 내부 대기 취소와 같이 WAITING 예약만 취소하고 대기 선두를 다시 확인합니다.",
        responses = [
            ApiResponse(responseCode = "204", description = "대기 취소 완료 또는 이미 취소된 대기"),
            ApiResponse(responseCode = "401", description = "관리자 JWT 인증 실패"),
            ApiResponse(responseCode = "403", description = "행사 APPLICATION_CANCEL 권한 없음"),
            ApiResponse(responseCode = "404", description = "예약 없음"),
            ApiResponse(responseCode = "409", description = "승급 처리 중이거나 대기 상태가 아님"),
            ApiResponse(responseCode = "502", description = "Expo 권한 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가"),
        ],
    )
    @PostMapping("/application/admin/expos/{expoId}/preregister-reservations/{requestId}/withdrawal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdraw(
        @PathVariable expoId: UUID,
        @PathVariable requestId: String,
        @RequestHeader("Authorization", required = false) bearer: String?,
    ) = withdrawal.execute(WithdrawAdminPreregisterReservationCommand(expoId, requestId, bearer))
}
