package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.AdminPromotionResult
import team.startup.application.domain.application.presentation.dto.PromoteAdminApplicationCommand
import team.startup.application.domain.application.service.PromoteAdminApplicationService
import java.util.UUID

@RestController
class AdminApplicationPromotionController(
    private val promotion: PromoteAdminApplicationService,
) {
    @Operation(
        summary = "관리자 대기 신청 수동 확정",
        description =
            "회차 정책의 자동 승급이 꺼졌거나 autoPromotionEndedAt이 지난 뒤 promotionEndedAt 전까지, Expo APPLICATION_PROMOTE 권한으로 " +
                "선택한 대기(학생·보호자 묶음은 묶음 전체)를 확정합니다. 정원·사전 좌석·진행 중 참여 제안 확보 좌석·자격·묶음·시간 충돌·" +
                "삭제·마감을 다시 확인하며 실패해도 대기를 유지합니다. 이미 확정이면 같은 결과를 돌려줍니다. 오류 본문은 {status, code}이며 " +
                "409 code는 AUTO_PROMOTION_ACTIVE·PROMOTION_ENDED·NOT_WAITING·OFFER_ACTIVE·CAPACITY_CHANGED·TIME_CONFLICT 등입니다.",
    )
    @PostMapping("/application/admin/expos/{expoId}/program-session-applications/{applicationId}/promotion")
    fun promote(
        @PathVariable expoId: UUID,
        @PathVariable applicationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<AdminPromotionResult> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(promotion.execute(PromoteAdminApplicationCommand(expoId, applicationId, authorization)))
}

// The admin screen needs the reason code to explain why a waiter cannot be confirmed yet.
@RestControllerAdvice(assignableTypes = [AdminApplicationPromotionController::class])
class AdminApplicationPromotionExceptionHandler {
    @ExceptionHandler(ProgramApplicationConflictException::class)
    fun conflict(ex: ProgramApplicationConflictException): ResponseEntity<PromotionOfferError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(PromotionOfferError(409, ex.message ?: "CONFLICT"))

    @ExceptionHandler(ResponseStatusException::class)
    fun expected(ex: ResponseStatusException): ResponseEntity<PromotionOfferError> =
        ResponseEntity.status(ex.statusCode).body(PromotionOfferError(ex.statusCode.value(), ex.reason ?: "ERROR"))
}
