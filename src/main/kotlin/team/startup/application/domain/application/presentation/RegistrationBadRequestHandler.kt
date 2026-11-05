package team.startup.application.domain.application.presentation

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import team.startup.application.domain.application.service.impl.RegistrationRequestRejectedException

@RestControllerAdvice(
    assignableTypes = [RegistrationController::class, OwnerApplicationController::class, FieldVisitController::class],
)
class RegistrationBadRequestHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(ex: ResponseStatusException): ResponseEntity<Map<String, Any>> {
        if (ex.statusCode.value() != 400) throw ex
        return registrationBadRequest(ex)
    }

    override fun createResponseEntity(
        body: Any?,
        headers: org.springframework.http.HttpHeaders,
        statusCode: org.springframework.http.HttpStatusCode,
        request: org.springframework.web.context.request.WebRequest,
    ): ResponseEntity<Any> =
        if (statusCode.value() == 400) {
            ResponseEntity.status(statusCode).headers(headers).body(invalidRegistrationRequest())
        } else {
            super.createResponseEntity(body, headers, statusCode, request)
        }
}

internal fun registrationBadRequest(ex: ResponseStatusException): ResponseEntity<Map<String, Any>> =
    ResponseEntity.badRequest().body(
        if (ex is RegistrationRequestRejectedException) {
            mapOf("status" to 400, "code" to ex.code, "message" to ex.reason.orEmpty())
        } else {
            invalidRegistrationRequest()
        },
    )

private fun invalidRegistrationRequest(): Map<String, Any> =
    mapOf("status" to 400, "code" to "INVALID_REQUEST", "message" to "입력한 신청 정보가 올바르지 않습니다. 입력 내용을 확인해 주세요.")
