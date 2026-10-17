package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ApplyStudentGuardianBatchCommand
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchReceipt
import team.startup.application.domain.application.service.ApplyStudentGuardianBatchService

@Service
class ApplyStudentGuardianBatchServiceImpl(
    private val admission: StudentGuardianBatchAdmission,
) : ApplyStudentGuardianBatchService {
    @Transactional
    override fun execute(command: ApplyStudentGuardianBatchCommand): StudentGuardianBatchReceipt = admission.admit(command, onsite = false)
}
