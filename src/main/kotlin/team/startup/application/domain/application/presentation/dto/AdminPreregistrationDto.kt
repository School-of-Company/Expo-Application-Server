package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(
        description = "취소·대기 철회 시각. 취소가 아니거나 #143 이전 확정 취소처럼 시각이 기록되지 않았으면 null",
    )
    val cancelledAt: Instant? = null,
    @field:Schema(description = "취소 주체. 취소가 아니면 null, 과거 미기록·인증 주체 없는 내부 철회는 UNKNOWN")
    val cancellationActorType: ApplicationActorType? = null,
    @field:Schema(
        description =
            "USER 취소의 실제 실행 대표자. 확정 취소는 User 참가자 ID와 현재 이름, " +
                "대기 철회는 예약 requestId와 신청 당시 이름",
    )
    val cancellationActorUser: ApplicationUserActor? = null,
    @field:Schema(description = "ADMIN 취소·대기 철회의 관리자 ID(#182). 그 외와 과거 미기록은 null")
    val cancellationActorId: Long? = null,
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

data class AdminPreregistrationCancellationRequest(
    @field:Size(min = 1, max = 5)
    val keys: List<
        @Size(min = 1, max = 160)
        String,
    >,
) : StrictPreregistrationRequest() {
    @get:AssertTrue(message = "keys must be unique")
    val isKeysUnique: Boolean get() = keys.distinct().size == keys.size
}

data class CancelAdminPreregistrationCommand(
    val expoId: UUID,
    val keys: List<String>,
    val authorization: String?,
)

data class AdminPreregistrationCancellationResult(
    val keys: List<String>,
    val status: String = "CANCELLED",
)

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
