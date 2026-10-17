package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.service.ApplyOnsiteProgramSessionService

@Service
class ApplyOnsiteProgramSessionServiceImpl(
    private val admission: ProgramSessionAdmission,
) : ApplyOnsiteProgramSessionService {
    @Transactional(noRollbackFor = [ProgramSessionAdmissionDeadlineException::class])
    override fun execute(request: ApplyProgramSessionCommand): ProgramSessionReceipt = admission.admit(request, onsite = true)
}
