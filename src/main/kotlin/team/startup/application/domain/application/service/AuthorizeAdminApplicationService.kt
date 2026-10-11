package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminExpoAccess
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand

interface AuthorizeAdminApplicationService {
    fun execute(command: AuthorizeAdminApplicationCommand): AdminExpoAccess
}
