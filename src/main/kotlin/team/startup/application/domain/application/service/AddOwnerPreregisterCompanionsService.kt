package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterCompanionsCommand
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterCompanionsResult

interface AddOwnerPreregisterCompanionsService {
    fun execute(command: AddOwnerPreregisterCompanionsCommand): OwnerPreregisterCompanionsResult
}
