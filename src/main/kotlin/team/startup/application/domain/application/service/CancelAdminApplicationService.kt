package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminCancellationResult
import team.startup.application.domain.application.presentation.dto.CancelAdminApplicationCommand

interface CancelAdminApplicationService {
    fun execute(command: CancelAdminApplicationCommand): AdminCancellationResult
}
