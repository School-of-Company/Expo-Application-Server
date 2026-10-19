package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistService
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistsService

// The latest waiting party, or the one for command.sessionId; the list operation owns the lookup (#198).
@Service
class GetOwnerPreregisterWaitlistServiceImpl(
    private val waitlists: GetOwnerPreregisterWaitlistsService,
) : GetOwnerPreregisterWaitlistService {
    override fun execute(command: OwnerPreregisterWaitlistCommand): OwnerPreregisterWaitlist =
        waitlists.execute(command).firstOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
