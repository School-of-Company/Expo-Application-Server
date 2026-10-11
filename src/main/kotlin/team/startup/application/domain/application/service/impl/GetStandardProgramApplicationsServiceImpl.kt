package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.StandardApplicationResponse
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.service.GetStandardProgramApplicationsService

@Service
class GetStandardProgramApplicationsServiceImpl(
    private val applications: StandardProgramApplicationRepository,
) : GetStandardProgramApplicationsService {
    @Transactional(readOnly = true)
    override fun execute(programId: Long): List<StandardApplicationResponse> =
        applications.findAllByStandardProgramIdOrderByIdAsc(programId).map { application ->
            StandardApplicationResponse(
                application.id!!,
                application.participantId,
            )
        }
}
