package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ProgramParticipantsCommand
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsResponse

interface GetProgramParticipantsService {
    fun execute(command: ProgramParticipantsCommand): ProgramParticipantsResponse
}
