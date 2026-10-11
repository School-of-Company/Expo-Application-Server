package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand

interface ApplyStandardProgramsService {
    fun execute(command: ApplyStandardProgramsCommand)
}
