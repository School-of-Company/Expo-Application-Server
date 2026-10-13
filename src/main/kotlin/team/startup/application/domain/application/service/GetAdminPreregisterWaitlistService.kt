package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlistCommand

interface GetAdminPreregisterWaitlistService {
    fun execute(command: AdminPreregisterWaitlistCommand): AdminPreregisterWaitlist
}
