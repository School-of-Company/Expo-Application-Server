package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** 참가자에게 보여 줄 문구만 전달한다. 입력값·문항 ID·공급자 오류는 넣지 않는다. */
class RegistrationRequestRejectedException(
    message: String,
    val code: String = "INVALID_REQUEST",
    cause: Throwable? = null,
) : ResponseStatusException(HttpStatus.BAD_REQUEST, message, cause)
