package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.TrainingApplicationVersionResponse
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.service.GetTrainingApplicationVersionService

@Service
class GetTrainingApplicationVersionServiceImpl(
    private val operations: TrainingOperationRepository,
) : GetTrainingApplicationVersionService {
    @Transactional(readOnly = true)
    override fun execute(traineeId: Long): TrainingApplicationVersionResponse {
        require(traineeId > 0)
        return TrainingApplicationVersionResponse(operations.version(traineeId))
    }
}
