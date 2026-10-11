package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.CheckTrainingProgramApplicationService

@Service
class CheckTrainingProgramApplicationServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
) : CheckTrainingProgramApplicationService {
    @Transactional(readOnly = true)
    override fun execute(
        programId: Long,
        traineeId: Long,
    ): Boolean = applications.existsByTraineeIdAndTrainingProgramId(traineeId, programId)
}
