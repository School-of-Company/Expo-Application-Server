package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.StandardApplicationResponse

interface GetStandardProgramApplicationsService {
    fun execute(programId: Long): List<StandardApplicationResponse>
}
