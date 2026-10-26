package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ProgramSessionSeatsQuery
import team.startup.application.domain.application.presentation.dto.ProgramSessionSeatsResponse

interface GetProgramSessionSeatsService {
    fun execute(query: ProgramSessionSeatsQuery): ProgramSessionSeatsResponse
}
