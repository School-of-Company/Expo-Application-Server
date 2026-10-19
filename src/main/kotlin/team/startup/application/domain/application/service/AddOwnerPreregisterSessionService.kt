package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterSessionResult

interface AddOwnerPreregisterSessionService {
    fun execute(command: AddOwnerPreregisterSessionCommand): OwnerPreregisterSessionResult
}
