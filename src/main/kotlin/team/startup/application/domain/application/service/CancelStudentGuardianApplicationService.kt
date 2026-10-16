package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CancelStudentGuardianApplicationCommand

interface CancelStudentGuardianApplicationService {
    fun execute(command: CancelStudentGuardianApplicationCommand)
}
