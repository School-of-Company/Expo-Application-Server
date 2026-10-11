package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand

interface CancelProgramSessionService {
    fun execute(command: CancelProgramSessionCommand)
}
