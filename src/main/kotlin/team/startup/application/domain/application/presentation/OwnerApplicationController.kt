package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.GetOwnerApplicationsService
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistService
import team.startup.application.domain.application.service.WithdrawOwnerPreregisterWaitlistService
import java.util.UUID

@RestController
@RequestMapping("/application/expos/{expoId}/my-applications")
class OwnerApplicationController(
    private val getApplications: GetOwnerApplicationsService,
    private val cancelApplication: CancelOwnerApplicationService,
    private val getWaitlist: GetOwnerPreregisterWaitlistService,
    private val withdrawWaitlist: WithdrawOwnerPreregisterWaitlistService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String,
) {
    @GetMapping
    @Operation(
        summary = "내 일행 신청 조회",
        description = "참가자 세션 쿠키로 일행을 확인합니다. 응답 X-CSRF-TOKEN과 JSESSIONID를 취소 요청에 사용합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "일행 신청 목록"),
            ApiResponse(responseCode = "400", description = "잘못된 박람회 ID"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "공급자 상태 충돌"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    fun get(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<OwnerApplicationsResponse> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getApplications.execute(GetOwnerApplicationsCommand(expoId, session(request))))

    @PostMapping("/{type}/{applicationId}/cancellation")
    @Operation(
        summary = "내 일행 신청 개별 취소",
        description = "참가자 세션과 CSRF 토큰이 필요합니다. 소유권과 취소 조건을 검사하며 공급자 보호 계약이 없으면 503입니다.",
        responses = [
            ApiResponse(responseCode = "204", description = "취소 완료"),
            ApiResponse(responseCode = "400", description = "잘못된 요청"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "취소 대상 없음"),
            ApiResponse(responseCode = "409", description = "취소 조건 또는 공급자 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "공급자 보호 계약 또는 연동 불가"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable expoId: UUID,
        @PathVariable type: OwnerApplicationType,
        @PathVariable applicationId: String,
        request: HttpServletRequest,
    ) = cancelApplication.execute(CancelOwnerApplicationCommand(expoId, session(request), type, applicationId))

    @GetMapping("/preregister-waitlist")
    @Operation(
        summary = "내 사전등록 대기 조회",
        description = "참가자 세션의 검증 번호로 대기 일행을 찾습니다. 대기 일행은 User 저장 전이라 QR이 없습니다. 응답 X-CSRF-TOKEN을 대기 취소에 사용합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "대기 또는 승급 처리 중인 일행"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "대기 일행 없음"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    fun waitlist(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<OwnerPreregisterWaitlist> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getWaitlist.execute(OwnerPreregisterWaitlistCommand(expoId, session(request))))

    @PostMapping("/preregister-waitlist/cancellation")
    @Operation(
        summary = "내 사전등록 대기 일행 전체 취소",
        description = "참가자 세션과 CSRF 토큰이 필요합니다. 취소된 대기는 같은 Idempotency-Key로 다시 만들 수 없고, 재신청은 새 키로 합니다.",
        responses = [
            ApiResponse(responseCode = "204", description = "대기 취소 완료"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "대기 일행 없음"),
            ApiResponse(responseCode = "409", description = "승급 처리 중이거나 삭제된 행사"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdraw(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
    ) = withdrawWaitlist.execute(OwnerPreregisterWaitlistCommand(expoId, session(request)))

    private fun session(request: HttpServletRequest): String =
        request.cookies
            ?.filter { it.name == sessionCookieName }
            ?.singleOrNull()
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "참가자 세션이 필요합니다.")
}
