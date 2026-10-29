package team.startup.application.global.exception

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLTransientConnectionException

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class DatabaseConnectionExceptionHandler {
    @ExceptionHandler(CannotGetJdbcConnectionException::class, SQLTransientConnectionException::class)
    fun unavailable(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Cache-Control", "no-store")
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database connection unavailable; retry the request"))
}
