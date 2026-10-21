package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import team.startup.application.domain.application.exception.SessionChangeConflictException

@Schema(description = "회차 변경 거절 사유")
data class SessionChangeConflict(
    @field:Schema(description = "HTTP 오류 상태", example = "409") val status: Int,
    @field:Schema(
        description = "TIME_LOCKED_BY_HISTORY, STRUCTURE_LOCKED_BY_HISTORY, BELOW_CONFIRMED_COUNT 또는 BELOW_WAITING_COUNT",
        example = "TIME_LOCKED_BY_HISTORY",
    )
    val code: String,
    @field:Schema(description = "관리자에게 보여 줄 거절 문구") val message: String,
)

/** 회차 변경 승인의 다른 오류 응답은 그대로 두고, 사유가 정해진 409에만 코드를 싣는다(#268). */
@RestControllerAdvice(
    assignableTypes = [
        PreregisterSessionChangeController::class,
        ProgramSessionWaitlistController::class,
        ProgramGuardianPolicyController::class,
    ],
)
class SessionChangeExceptionHandler {
    @ExceptionHandler(SessionChangeConflictException::class)
    fun conflict(ex: SessionChangeConflictException): ResponseEntity<SessionChangeConflict> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(SessionChangeConflict(409, ex.code.name, ex.code.message))
}
