package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey

interface ReconcilePreregisterSessionService {
    fun execute(command: PreregisterSessionKey): Boolean
}
