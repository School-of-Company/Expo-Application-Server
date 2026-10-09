package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt

interface CancelPreregisterSessionService {
    fun execute(command: CancelPreregisterSessionCommand): List<PreregisterApplicationReceipt>
}
