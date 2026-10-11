package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

enum class TrainingOperationType { ADD, REPLACE }

@Schema(description = "연수 신청 작업의 완료 영수증")
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

@Schema(description = "연수자의 현재 신청 버전", example = """{"version":1}""")
data class TrainingApplicationVersionResponse(
    val version: Long,
)
