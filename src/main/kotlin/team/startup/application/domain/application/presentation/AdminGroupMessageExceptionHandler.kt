package team.startup.application.domain.application.presentation

import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.service.impl.AdminExpoGateway

@RestControllerAdvice(
    assignableTypes = [
        AdminGroupMessageController::class,
        AdminSourceHistoryController::class,
        AdminApplicationCancellationController::class,
        LegacyTraineeAttributionController::class,
    ],
)
@Order(0)
class AdminGroupMessageExceptionHandler {
    @ExceptionHandler(ProgramApplicationConflictException::class)
    fun conflict(): ResponseEntity<ProblemDetail> = error(409, "Application cancellation conflicts with the current program policy")

    @ExceptionHandler(ResponseStatusException::class)
    fun expected(exception: ResponseStatusException): ResponseEntity<ProblemDetail> =
        adminAccessDenial(exception) ?: error(exception.statusCode.value(), exception.reason ?: "Admin group message request failed")

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(): ResponseEntity<ProblemDetail> = error(400, "Invalid admin group message request")

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(): ResponseEntity<ProblemDetail> = error(503, "Application source unavailable; retry the request")

    private fun error(
        status: Int,
        detail: String,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(status)
            .header("Cache-Control", "no-store")
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail))
}

// #267: 관리자 권한 거절 사유 코드면 ProblemDetail에 code와 고정 문구를 싣는다. 아니면 null로 호출자의 기존 응답을 유지한다.
fun adminAccessDenial(exception: ResponseStatusException): ResponseEntity<ProblemDetail>? {
    val detail = AdminExpoGateway.ACCESS_DENIALS[exception.reason] ?: return null
    return ResponseEntity
        .status(exception.statusCode)
        .header("Cache-Control", "no-store")
        .body(ProblemDetail.forStatusAndDetail(exception.statusCode, detail).apply { setProperty("code", exception.reason) })
}
