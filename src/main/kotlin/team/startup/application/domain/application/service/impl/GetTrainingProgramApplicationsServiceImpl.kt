package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.TrainingApplicationResponse
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.GetTrainingProgramApplicationsService

@Service
class GetTrainingProgramApplicationsServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
) : GetTrainingProgramApplicationsService {
    @Transactional(readOnly = true)
    override fun execute(programId: Long): List<TrainingApplicationResponse> =
        applications.findAllByTrainingProgramIdOrderByIdAsc(programId).map { application ->
            TrainingApplicationResponse(
                applicationId = requireNotNull(application.id),
                traineeId = application.traineeId,
            )
        }
}
