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

enum class AdminOccupation {
    KINDERGARTEN_STUDENT,
    ELEMENTARY_STUDENT,
    MIDDLE_SCHOOL_STUDENT,
    HIGH_SCHOOL_STUDENT,
    SCHOOL_STAFF,
    PARENT,
    GENERAL,
    TEACHER,
    PRE_SERVICE_TEACHER,
}

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
    @field:Size(min = 1, max = 50) val q: String? = null,
    val occupation: AdminOccupation? = null,
) {
    @get:AssertTrue(message = "q must not be blank")
    val isParticipantQueryValid: Boolean get() = q == null || q.isNotBlank()

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
    val participant: AdminParticipantDetail? = null,
)

data class AdminApplicationReadCommand(
    val expoId: UUID,
    val query: AdminApplicationQuery,
    val includeParticipantDetails: Boolean,
)

data class AdminParticipantDetail(
    val participantId: Long,
    val name: String,
    val phoneNumber: String?,
    val notificationPhoneNumber: String?,
    val occupation: AdminOccupation?,
    val representativeId: Long?,
    val linkedTraineeId: Long?,
    val applicationType: String,
    val information: JsonNode,
)

data class AdminExpoAccess(
    val expoId: UUID,
    val adminId: Long,
    val permission: String,
    val policyVersion: String,
    val scope: String,
)

data class AuthorizeAdminApplicationCommand(
    val expoId: UUID,
    val authorization: String?,
)

data class AdminCurrentSessionQuery(
    val programType: ProgramSessionType? = null,
    @field:Positive val programId: Long? = null,
    val groupId: UUID? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    @field:Size(min = 1, max = 100) val definitionVersion: String? = null,
) {
    @get:AssertTrue(message = "programId requires programType; subsequent pages require definitionVersion")
    val isSelectionValid: Boolean get() = (programId == null || programType != null) && (page == 0 || !definitionVersion.isNullOrBlank())
}

data class AdminCurrentSessionPage(
    val expoId: UUID,
    val items: List<JsonNode>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val definitionVersion: String,
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
