package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.media.Schema
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException

@Schema(description = "프로그램 신청 오류")
data class ProgramSessionError(
    @field:Schema(description = "HTTP 오류 상태", example = "400") val status: Int,
    @field:Schema(description = "클라이언트가 처리할 오류 종류") val code: String,
)

@RestControllerAdvice(assignableTypes = [ProgramSessionApplicationController::class, ManagedParticipantController::class, StudentGuardianBatchController::class])
class ProgramSessionApplicationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ResponseStatusException::class)
    fun upstream(ex: ResponseStatusException): ResponseEntity<ProgramSessionError> {
        val status = ex.statusCode.value()
        // Every 409 reaches the client as APPLICATION_CONFLICT; the fixed reason identifies the failing stage (#122).
        if (status == 409) log.warn("Program application conflict: {}", ex.reason)
        val code =
            if (status == 401 && ex.reason in setOf("SESSION_REQUIRED", "SESSION_EXPIRED")) {
                ex.reason!!
            } else {
                when (status) {
                    400 -> "INVALID_APPLICATION"
                    401 -> "SESSION_REQUIRED"
                    403 -> "PARTICIPANT_INELIGIBLE"
                    404 -> "APPLICATION_TARGET_NOT_FOUND"
                    409 -> "APPLICATION_CONFLICT"
                    502 -> "INVALID_SUPPLIER_RESPONSE"
                    else -> "SUPPLIER_UNAVAILABLE"
                }
            }
        return ResponseEntity.status(status).body(ProgramSessionError(status, code))
    }

    @ExceptionHandler(ProgramApplicationConflictException::class)
    fun conflict(ex: ProgramApplicationConflictException): ResponseEntity<ProgramSessionError> {
        log.warn("Program application conflict: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProgramSessionError(409, "APPLICATION_CONFLICT"))
    }

    @ExceptionHandler(
        org.springframework.web.bind.MethodArgumentNotValidException::class,
        org.springframework.http.converter.HttpMessageNotReadableException::class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,
    )
    fun invalid(): ResponseEntity<ProgramSessionError> = ResponseEntity.badRequest().body(ProgramSessionError(400, "INVALID_APPLICATION"))
}
