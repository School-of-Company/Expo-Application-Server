package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregistrationCancellationResult
import team.startup.application.domain.application.presentation.dto.CancelAdminPreregistrationCommand

interface CancelAdminPreregistrationService {
    fun execute(command: CancelAdminPreregistrationCommand): AdminPreregistrationCancellationResult
}
