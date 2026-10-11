package team.startup.application.domain.application.presentation

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
import tools.jackson.core.JacksonException

@RestControllerAdvice(assignableTypes = [ReportApplicationTargetController::class])
class ReportApplicationTargetExceptionHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun expected(exception: ResponseStatusException): ResponseEntity<ProblemDetail> =
        error(exception.statusCode.value(), exception.reason ?: "Report target query failed")

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        org.springframework.web.bind.MissingServletRequestParameterException::class,
    )
    fun invalid(): ResponseEntity<ProblemDetail> = error(400, "Invalid report target request")

    @ExceptionHandler(DataAccessException::class, JacksonException::class)
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
