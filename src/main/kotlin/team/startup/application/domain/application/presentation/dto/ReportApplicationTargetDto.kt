package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

abstract class StrictReportTargetRequest {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}

data class ReportApplicationFilter(
    val programType: ProgramSessionType? = null,
    val programId: Long? = null,
    val groupId: UUID? = null,
    val applicationId: UUID? = null,
    val status: AdminApplicationStatus? = null,
    val search: String? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    val sort: AdminApplicationSort = AdminApplicationSort.CREATED_DESC,
    @field:Size(min = 1, max = 50) val q: String? = null,
    val occupation: AdminOccupation? = null,
) : StrictReportTargetRequest() {
    @get:Valid
    @get:JsonIgnore
    val query: AdminApplicationQuery
        get() =
            AdminApplicationQuery(
                programType,
                programId,
                groupId,
                applicationId,
                status,
                search,
                from,
                until,
                sort = sort,
                q = q,
                occupation = occupation,
            )

    @get:AssertTrue(message = "search must not be blank")
    @get:JsonIgnore
    val isSearchValid: Boolean get() = (search == null || search.isNotBlank()) && (q == null || q.isNotBlank())
}

data class SelectReportApplicationTargetsRequest(
    @field:Size(min = 1, max = 1000) val applicationIds: List<UUID>,
) : StrictReportTargetRequest() {
    @get:AssertTrue(message = "applicationIds must be unique")
    @get:JsonIgnore
    val isSelectionUnique: Boolean get() = applicationIds.distinct().size == applicationIds.size
}

data class CreateReportApplicationSnapshotRequest(
    @field:Valid val filter: ReportApplicationFilter = ReportApplicationFilter(),
    @field:Min(1) @field:Max(500) val limit: Int = 100,
) : StrictReportTargetRequest()

data class ReportRegistrationSource(
    val expoId: UUID,
    val participantId: Long,
)

data class ReportApplicationTarget(
    val expoId: UUID,
    val application: AdminApplicationRow,
    val registrationSource: ReportRegistrationSource,
)

data class ReportApplicationSelection(
    val items: List<ReportApplicationTarget>,
    val totalElements: Int,
)

data class ReportApplicationSnapshotPage(
    val snapshotId: UUID,
    val snapshotAt: Instant,
    val expiresAt: Instant,
    val totalElements: Int,
    val items: List<ReportApplicationTarget>,
    val nextCursor: String?,
)
