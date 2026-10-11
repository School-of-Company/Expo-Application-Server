package team.startup.application.domain.application.presentation.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class AdminApplicationStatus { CONFIRMED, WAITING, CANCELLED }

enum class AdminApplicationSort { CREATED_ASC, CREATED_DESC }

data class AdminApplicationQuery(
    val programType: ProgramSessionType? = null,
    @field:Positive val programId: Long? = null,
    val groupId: UUID? = null,
    val applicationId: UUID? = null,
    val status: AdminApplicationStatus? = null,
    @field:Size(max = 200) val search: String? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    val sort: AdminApplicationSort = AdminApplicationSort.CREATED_DESC,
) {
    @get:AssertTrue(message = "from must precede until")
    val isDateRangeValid: Boolean get() = from == null || until == null || from < until

    @get:AssertTrue(message = "programId requires programType")
    val isProgramTypeValid: Boolean get() = programId == null || programType != null
}

data class AdminApplicationRow(
    val applicationId: UUID,
    val participantId: Long,
    val traineeId: Long?,
    val programType: ProgramSessionType,
    val programId: Long,
    val groupId: UUID,
    val status: AdminApplicationStatus,
    val createdAt: Instant,
    val formId: UUID,
    val formVersion: Long,
    val definition: JsonNode,
    val questions: JsonNode,
    val answers: JsonNode,
    val history: List<AdminApplicationHistory>,
)

data class AdminApplicationHistory(
    val id: Long,
    val status: AdminApplicationStatus,
    val createdAt: Instant,
)

data class AdminApplicationPage(
    val items: List<AdminApplicationRow>,
    val totalElements: Long,
    val statusCounts: Map<AdminApplicationStatus, Long>,
    val page: Int,
    val size: Int,
)

data class AdminApplicationSummary(
    val totalElements: Long,
    val uniqueParticipants: Long,
    val statusCounts: Map<AdminApplicationStatus, Long>,
    val totalSessions: Long,
    val sessions: List<AdminApplicationSessionSummary>,
    val page: Int,
    val size: Int,
)

data class AdminApplicationSessionSummary(
    val programType: ProgramSessionType,
    val programId: Long,
    val groupId: UUID,
    val totalElements: Long,
    val uniqueParticipants: Long,
    val statusCounts: Map<AdminApplicationStatus, Long>,
    val attendance: AdminProgramAttendanceSummary? = null,
)

data class AdminProgramAttendanceSummary(
    val programType: ProgramSessionType,
    val programId: Long,
    val attendeeCount: Long,
    val firstEntryTime: String?,
    val lastEntryTime: String?,
)
