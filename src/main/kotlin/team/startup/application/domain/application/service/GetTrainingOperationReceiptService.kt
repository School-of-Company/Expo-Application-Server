package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.TrainingOperationReceipt
import java.util.UUID

interface GetTrainingOperationReceiptService {
    fun execute(operationId: UUID): TrainingOperationReceipt?
}
