package team.startup.application.domain.application.presentation.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

enum class AdminMessageHistoryType { REQUESTED, SENT, FAILED, RETRIED }

data class AdminMessageHistoryQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    val type: AdminMessageHistoryType? = null,
    @field:Min(1) @field:Max(100) val limit: Int = 20,
    @field:Pattern(regexp = "^\\d{13}:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") val cursor: String? = null,
)

data class AdminMessageHistoryScope(
    val kind: String,
    val applicationCount: Int?,
    val programTypes: List<ProgramSessionType>,
)

data class AdminMessageHistoryCounts(
    val total: Long,
    val pending: Long,
    val accepted: Long,
    val delivered: Long,
    val failed: Long,
    val unknown: Long,
    val missing: Long,
)

data class AdminMessageHistoryItem(
    val eventId: UUID,
    val type: AdminMessageHistoryType,
    val adminId: Long,
    val occurredAt: Instant,
    val targetScope: AdminMessageHistoryScope,
    val counts: AdminMessageHistoryCounts,
    val status: AdminGroupMessageJobStatus,
    val jobId: String,
)

data class AdminMessageHistoryPage(
    val items: List<AdminMessageHistoryItem>,
    val nextCursor: String? = null,
)

enum class AdminExpoHistoryType { PROGRAM_CREATE, PROGRAM_UPDATE, PROGRAM_DELETE, SESSION_CREATE, SESSION_UPDATE, SESSION_DELETE }

data class AdminExpoHistoryQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    val type: AdminExpoHistoryType? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
)

data class AdminExpoHistoryItem(
    val id: Long,
    val type: AdminExpoHistoryType,
    val adminId: Long?,
    val occurredAt: Instant,
    val targetType: String,
    val targetId: Long,
    val summary: String,
)

data class AdminExpoHistoryPage(
    val expoId: UUID,
    val items: List<AdminExpoHistoryItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
