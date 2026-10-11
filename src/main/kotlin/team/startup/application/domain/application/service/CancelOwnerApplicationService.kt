package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand

interface CancelOwnerApplicationService {
    fun execute(command: CancelOwnerApplicationCommand)
}
