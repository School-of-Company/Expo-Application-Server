package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminApplicationPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationReadCommand

interface GetAdminApplicationsService {
    fun execute(command: AdminApplicationReadCommand): AdminApplicationPage
}
