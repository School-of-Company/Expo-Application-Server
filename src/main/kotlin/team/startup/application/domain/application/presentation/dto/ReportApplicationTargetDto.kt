package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
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

@Schema(description = "보고 대상 필터. 관리 원장 필터와 동일한 의미")
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

@Schema(description = "중복 없는 UUID 신청 선택. 최대 1000건", example = """{"applicationIds":["0199c000-0000-7000-8000-000000000001"]}""")
data class SelectReportApplicationTargetsRequest(
    @field:Size(min = 1, max = 1000) val applicationIds: List<UUID>,
) : StrictReportTargetRequest() {
    @get:AssertTrue(message = "applicationIds must be unique")
    @get:JsonIgnore
    val isSelectionUnique: Boolean get() = applicationIds.distinct().size == applicationIds.size
}

@Schema(description = "전체 보고 대상 스냅샷 생성. 페이지 상한 500건", example = """{"filter":{},"limit":100}""")
data class CreateReportApplicationSnapshotRequest(
    @field:Valid val filter: ReportApplicationFilter = ReportApplicationFilter(),
    @field:Min(1) @field:Max(500) val limit: Int = 100,
) : StrictReportTargetRequest()

@Schema(description = "등록 원천을 찾기 위한 박람회·참가자 참조")
data class ReportRegistrationSource(
    val expoId: UUID,
    val participantId: Long,
)

@Schema(description = "신청 원장과 등록 원천 참조")
data class ReportApplicationTarget(
    val expoId: UUID,
    val application: AdminApplicationRow,
    val registrationSource: ReportRegistrationSource,
)

@Schema(description = "선택한 보고 대상 목록")
data class ReportApplicationSelection(
    val items: List<ReportApplicationTarget>,
    val totalElements: Int,
)

@Schema(description = "15분간 고정된 보고 대상 페이지와 다음 커서")
data class ReportApplicationSnapshotPage(
    val snapshotId: UUID,
    val snapshotAt: Instant,
    val expiresAt: Instant,
    val totalElements: Int,
    val items: List<ReportApplicationTarget>,
    val nextCursor: String?,
)
