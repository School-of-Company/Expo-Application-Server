package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey

interface PromotePreregisterSessionService {
    fun execute(command: PreregisterSessionKey): List<PreregisterApplicationReceipt>
}
