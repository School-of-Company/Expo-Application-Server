package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand

interface PrepareProgramChangeService {
    fun execute(command: PrepareProgramChangeCommand)
}
