package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyStudentGuardianBatchCommand
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchReceipt

interface ApplyOnsiteStudentGuardianBatchService {
    fun execute(command: ApplyStudentGuardianBatchCommand): StudentGuardianBatchReceipt
}
