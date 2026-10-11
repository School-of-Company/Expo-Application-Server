package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt

interface ApplyPreregisterSessionService {
    fun execute(command: ApplyPreregisterSessionCommand): List<PreregisterApplicationReceipt>
}
