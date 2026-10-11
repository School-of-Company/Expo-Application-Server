package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt

interface ApplyProgramSessionService {
    fun execute(request: ApplyProgramSessionCommand): ProgramSessionReceipt
}
