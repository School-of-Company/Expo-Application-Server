package team.startup.application.domain.application.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason

@ResponseStatus(HttpStatus.CONFLICT)
open class ProgramApplicationConflictException(
    message: String,
    val ineligibleReason: ProgramIneligibleReason? = null,
) : RuntimeException(message)
