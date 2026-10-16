package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class AdminPreregistrationStatus { CONFIRMED, WAITING, CANCELLED, PENDING }

abstract class StrictPreregistrationRequest {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}

data class AdminPreregistrationQuery(
    @field:Positive val sessionId: Long? = null,
    @param:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    @field:Size(min = 1) val status: List<AdminPreregistrationStatus>? = null,
    @field:Size(min = 1, max = 50) val q: String? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
) : StrictPreregistrationRequest() {
    @get:AssertTrue(message = "q must not be blank")
    val isQueryValid: Boolean get() = q == null || q.isNotBlank()
}

data class AdminPreregistrationRow(
    val key: String,
    val expoId: UUID,
    val sessionId: Long,
    val status: AdminPreregistrationStatus,
    val applicationId: Long?,
    val reservationId: String?,
    val participantId: Long?,
    val representativeId: Long?,
    val applicants: Int,
    val createdAt: Instant?,
    val participant: AdminParticipantDetail? = null,
    val reservation: JsonNode? = null,
)

data class AdminPreregistrationPage(
    val items: List<AdminPreregistrationRow>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)

data class AdminPreregistrationReadCommand(
    val expoId: UUID,
    val query: AdminPreregistrationQuery,
)

data class AdminPreregistrationDetailCommand(
    val expoId: UUID,
    val key: String,
)

enum class AdminPreregistrationScope { SELECTED, FILTERED }

data class AdminPreregistrationTargetsRequest(
    val kind: AdminPreregistrationScope,
    @field:Size(min = 1, max = 5000)
    val keys: List<
        @Size(min = 1, max = 160)
        String,
    >? = null,
    @field:Valid val filter: AdminPreregistrationQuery? = null,
) : StrictPreregistrationRequest() {
    @get:AssertTrue(message = "SELECTED requires unique keys only; FILTERED requires an unpaged filter only")
    val isScopeValid: Boolean
        get() =
            when (kind) {
                AdminPreregistrationScope.SELECTED -> !keys.isNullOrEmpty() && keys.distinct().size == keys.size && filter == null
                AdminPreregistrationScope.FILTERED -> keys == null && filter != null && filter.page == 0 && filter.size == 20
            }
}

data class AdminPreregistrationTargetsCommand(
    val expoId: UUID,
    val request: AdminPreregistrationTargetsRequest,
)

data class AdminPreregistrationTargets(
    val expoId: UUID,
    val tab: String = "PREREGISTRATION",
    val items: List<AdminPreregistrationRow>,
    val totalElements: Long,
    val reportSupported: Boolean = false,
    val messagingSupported: Boolean = false,
)
