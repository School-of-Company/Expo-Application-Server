package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ProgramSessionKey

interface ReconcileProgramSessionService {
    fun execute(command: ProgramSessionKey)
}
