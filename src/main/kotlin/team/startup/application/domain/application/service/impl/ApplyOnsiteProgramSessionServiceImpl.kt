package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.service.ApplyOnsiteProgramSessionService

@Service
class ApplyOnsiteProgramSessionServiceImpl(
    private val admission: ProgramSessionAdmission,
) : ApplyOnsiteProgramSessionService {
    // #212: Expo·Form 호출을 트랜잭션 밖에 두려고 접수가 잠그는 구간의 트랜잭션을 직접 연다.
    override fun execute(request: ApplyProgramSessionCommand): ProgramSessionReceipt = admission.admit(request, onsite = true)
}
