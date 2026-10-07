package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.service.DeleteStandardProgramApplicationsService

@Service
class DeleteStandardProgramApplicationsServiceImpl(
    private val applications: StandardProgramApplicationRepository,
) : DeleteStandardProgramApplicationsService {
    @Transactional
    override fun execute(programId: Long) {
        applications.lockProgram(programId)
        applications.markDeleted(programId)
        applications.deleteAllByStandardProgramId(programId)
    }
}
