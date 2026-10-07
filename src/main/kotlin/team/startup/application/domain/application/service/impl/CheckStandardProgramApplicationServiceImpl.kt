package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.service.CheckStandardProgramApplicationService

@Service
class CheckStandardProgramApplicationServiceImpl(
    private val applications: StandardProgramApplicationRepository,
) : CheckStandardProgramApplicationService {
    @Transactional(readOnly = true)
    override fun execute(
        programId: Long,
        participantId: Long,
    ): Boolean = applications.existsByParticipantIdAndStandardProgramId(participantId, programId)
}
