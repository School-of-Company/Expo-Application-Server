package team.startup.application.domain.application.presentation.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant
import java.util.UUID

enum class AdminApplicationAction { APPLIED, CANCELLED, PROMOTED }

data class AdminApplicationActivityQuery(
    val action: AdminApplicationAction? = null,
    val applicationId: UUID? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
)

data class AdminApplicationActivity(
    val id: Long,
    val applicationId: UUID,
    val participantId: Long,
    val programType: ProgramSessionType,
    val programId: Long,
    val action: AdminApplicationAction,
    val status: AdminApplicationStatus,
    val actorId: Long?,
    val createdAt: Instant,
)

data class AdminApplicationActivityPage(
    val items: List<AdminApplicationActivity>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)
