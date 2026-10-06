package team.startup.application.domain.application.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.CONFLICT)
class ProgramApplicationConflictException(
    message: String,
) : RuntimeException(message)
