package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregistrationPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationReadCommand

interface GetAdminPreregistrationsService {
    fun execute(command: AdminPreregistrationReadCommand): AdminPreregistrationPage
}
