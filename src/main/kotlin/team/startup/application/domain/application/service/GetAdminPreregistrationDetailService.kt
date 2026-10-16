package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregistrationDetailCommand
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow

interface GetAdminPreregistrationDetailService {
    fun execute(command: AdminPreregistrationDetailCommand): AdminPreregistrationRow
}
