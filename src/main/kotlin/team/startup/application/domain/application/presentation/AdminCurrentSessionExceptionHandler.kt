package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import team.startup.application.domain.application.exception.ProgramDefinitionMismatchException
import team.startup.application.domain.application.exception.ProgramDefinitionVersionChangedException
import team.startup.application.domain.application.presentation.dto.ProgramSessionType

@Schema(description = "현재 회차 조회 충돌")
data class AdminCurrentSessionConflict(
    @field:Schema(description = "HTTP 오류 상태", example = "409") val status: Int,
    @field:Schema(description = "PROGRAM_DEFINITION_MISMATCH 또는 DEFINITION_VERSION_CHANGED") val code: String,
    @field:Schema(description = "정의가 맞지 않는 회차 유형. 버전 변경이면 null") val programType: ProgramSessionType? = null,
    @field:Schema(description = "정의가 맞지 않는 회차 ID. 버전 변경이면 null") val programId: Long? = null,
)

/** 관리자 원장 컨트롤러의 다른 오류 응답은 바꾸지 않고, 현재 회차 정의 충돌 두 가지만 원인 코드로 구분한다(#163). */
@RestControllerAdvice(assignableTypes = [AdminApplicationController::class])
class AdminCurrentSessionExceptionHandler {
    @ExceptionHandler(ProgramDefinitionMismatchException::class)
    fun mismatch(ex: ProgramDefinitionMismatchException): ResponseEntity<AdminCurrentSessionConflict> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(AdminCurrentSessionConflict(409, "PROGRAM_DEFINITION_MISMATCH", ex.programType, ex.programId))

    @ExceptionHandler(ProgramDefinitionVersionChangedException::class)
    fun versionChanged(): ResponseEntity<AdminCurrentSessionConflict> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(AdminCurrentSessionConflict(409, "DEFINITION_VERSION_CHANGED"))
}
