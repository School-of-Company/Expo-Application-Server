package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.TrainingOperationReceipt
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.service.GetTrainingOperationReceiptService
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class GetTrainingOperationReceiptServiceImpl(
    private val operations: TrainingOperationRepository,
    private val mapper: ObjectMapper,
) : GetTrainingOperationReceiptService {
    @Transactional(readOnly = true)
    override fun execute(operationId: UUID): TrainingOperationReceipt? =
        operations.find(operationId)?.let { mapper.readValue(it.second, TrainingOperationReceipt::class.java) }
}
