package team.startup.application.domain.application.presentation.dto

import java.time.Instant
import java.util.UUID

enum class TrainingOperationType { ADD, REPLACE }

data class TrainingOperationReceipt(
    val operationId: UUID,
    val operationType: TrainingOperationType,
    val expoId: String,
    val traineeId: Long,
    val version: Long,
    val changed: Boolean,
    val programIds: List<Long>,
    val completedAt: Instant,
    val status: String = "SUCCEEDED",
)

data class TrainingApplicationVersionResponse(
    val version: Long,
)
