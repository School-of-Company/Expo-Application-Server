package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.TrainingApplicationResponse
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.GetTrainingProgramApplicationsService
import java.time.format.DateTimeFormatter

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
                status = application.status,
                entryTime = application.entryTime?.format(TIME_FORMAT),
                leaveTime = application.leaveTime?.format(TIME_FORMAT),
            )
        }

    private companion object {
        val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}
