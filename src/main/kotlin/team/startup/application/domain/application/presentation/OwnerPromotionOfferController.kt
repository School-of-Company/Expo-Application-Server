package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOffer
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOfferCommand
import team.startup.application.domain.application.service.AcceptPromotionOfferService
import team.startup.application.domain.application.service.DeclinePromotionOfferService
import team.startup.application.domain.application.service.GetOwnerPromotionOffersService
import java.util.UUID

@RestController
@RequestMapping("/application/expos/{expoId}/my-applications/promotion-offers")
class OwnerPromotionOfferController(
    private val getOffers: GetOwnerPromotionOffersService,
    private val acceptOffer: AcceptPromotionOfferService,
    private val declineOffer: DeclinePromotionOfferService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String,
) {
    @GetMapping
    @Operation(
        summary = "내 대기 참여 제안 조회",
        description = "참가자 세션 쿠키로 대표자의 참여 제안을 찾습니다. 응답 X-CSRF-TOKEN 헤더와 XSRF-TOKEN 쿠키를 수락·거절에 사용합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "참여 제안 목록(없으면 빈 배열)"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    fun get(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<List<OwnerPromotionOffer>> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getOffers.execute(OwnerPromotionOfferCommand(expoId, session(request))))

    @PostMapping("/{offerId}/acceptance")
    @Operation(
        summary = "참여 제안 수락",
        description = "참가자 세션과 CSRF 토큰이 필요합니다. 수락 시 자격·시간 충돌·묶음·정원을 다시 확인하고, 통과해야만 확정합니다. 같은 요청을 반복하면 같은 결과를 돌려줍니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "수락 결과(ACCEPTED)"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "본인 제안 아님 또는 없음"),
            ApiResponse(responseCode = "409", description = "응답 기한 경과, 이미 거절·종료, 또는 재검증 실패(code에 사유)"),
            ApiResponse(responseCode = "503", description = "공급자 연동 불가 또는 처리 중. 같은 요청으로 재시도"),
        ],
    )
    fun accept(
        @PathVariable expoId: UUID,
        @PathVariable offerId: UUID,
        request: HttpServletRequest,
    ): OwnerPromotionOffer = acceptOffer.execute(OwnerPromotionOfferCommand(expoId, session(request), offerId))

    @PostMapping("/{offerId}/decline")
    @Operation(
        summary = "참여 제안 거절",
        description = "참가자 세션과 CSRF 토큰이 필요합니다. 거절하면 대기가 종료되고 다음 대기자에게 제안합니다. 반복 요청은 같은 결과입니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "거절 결과(DECLINED)"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "본인 제안 아님 또는 없음"),
            ApiResponse(responseCode = "409", description = "이미 수락·만료·종료된 제안"),
            ApiResponse(responseCode = "503", description = "공급자 연동 불가"),
        ],
    )
    fun decline(
        @PathVariable expoId: UUID,
        @PathVariable offerId: UUID,
        request: HttpServletRequest,
    ): OwnerPromotionOffer = declineOffer.execute(OwnerPromotionOfferCommand(expoId, session(request), offerId))

    private fun session(request: HttpServletRequest): String =
        request.cookies
            ?.filter { it.name == sessionCookieName }
            ?.singleOrNull()
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED")
}

data class PromotionOfferError(
    val status: Int,
    val code: String,
)

// Client needs the reason code (OFFER_EXPIRED, TIME_CONFLICT, SESSION_EXPIRED…) to show the right state.
@RestControllerAdvice(assignableTypes = [OwnerPromotionOfferController::class])
class OwnerPromotionOfferExceptionHandler {
    @ExceptionHandler(ProgramApplicationConflictException::class)
    fun conflict(ex: ProgramApplicationConflictException): ResponseEntity<PromotionOfferError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(PromotionOfferError(409, ex.message ?: "CONFLICT"))

    @ExceptionHandler(ResponseStatusException::class)
    fun expected(ex: ResponseStatusException): ResponseEntity<PromotionOfferError> =
        ResponseEntity.status(ex.statusCode).body(PromotionOfferError(ex.statusCode.value(), ex.reason ?: "ERROR"))
}
