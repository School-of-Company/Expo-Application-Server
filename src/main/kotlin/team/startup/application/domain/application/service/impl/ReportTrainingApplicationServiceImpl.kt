package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.TrainingApplicationForReportResponse
import team.startup.application.domain.application.presentation.dto.TrainingApplicationsByTraineesRequest
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ReportTrainingApplicationService

@Service
class ReportTrainingApplicationServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
) : ReportTrainingApplicationService {
    @Transactional(readOnly = true)
    override fun execute(request: TrainingApplicationsByTraineesRequest): List<TrainingApplicationForReportResponse> {
        require(request.traineeIds.all { it > 0 })
        if (request.traineeIds.isEmpty()) return emptyList()
        return applications.findAllByTraineeIdIn(request.traineeIds).sortedBy { requireNotNull(it.id) }.map { application ->
            TrainingApplicationForReportResponse(
                applicationId = requireNotNull(application.id),
                traineeId = application.traineeId,
                trainingProgramId = application.trainingProgramId,
            )
        }
    }
}
