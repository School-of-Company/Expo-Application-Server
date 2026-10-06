package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand
import team.startup.application.domain.application.presentation.dto.StandardApplicationResponse

interface StandardProgramApplicationService {
    fun execute(command: ApplyStandardProgramsCommand)

    fun list(programId: Long): List<StandardApplicationResponse>

    fun delete(programId: Long)
}
