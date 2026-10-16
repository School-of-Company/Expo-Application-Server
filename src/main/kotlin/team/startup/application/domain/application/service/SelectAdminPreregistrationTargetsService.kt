package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargets
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargetsCommand

interface SelectAdminPreregistrationTargetsService {
    fun execute(command: AdminPreregistrationTargetsCommand): AdminPreregistrationTargets
}
