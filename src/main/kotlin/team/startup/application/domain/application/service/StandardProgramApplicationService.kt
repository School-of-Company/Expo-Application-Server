package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand

interface StandardProgramApplicationService {
    fun execute(command: ApplyStandardProgramsCommand)
}
