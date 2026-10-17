package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

abstract class StrictAdminGroupMessageRequest {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}

enum class AdminGroupMessageScopeKind { SELECTED, FILTERED }

enum class AdminGroupMessageChannel { SMS, KAKAO }

data class AdminGroupMessageScope(
    val kind: AdminGroupMessageScopeKind,
    @field:Size(min = 1, max = 1000) val applicationIds: List<UUID>? = null,
    @field:Valid val filter: ReportApplicationFilter? = null,
) : StrictAdminGroupMessageRequest() {
    @get:AssertTrue(message = "scope must contain exactly one matching selector")
    @get:JsonIgnore
    val isSelectorValid: Boolean
        get() =
            when (kind) {
                AdminGroupMessageScopeKind.SELECTED -> applicationIds != null && filter == null
                AdminGroupMessageScopeKind.FILTERED -> applicationIds == null && filter != null
            }

    // ponytail: 발송은 별도 User 토큰을 쓰므로 목록용 사람 후보 조회를 섞지 않는다. 무시하면 대상이 넓어지므로 거부한다.
    @get:AssertTrue(message = "filtered group messages do not support q or occupation; select applicationIds instead")
    @get:JsonIgnore
    val isPersonFilterAbsent: Boolean get() = filter == null || (filter.q == null && filter.occupation == null)

    @get:AssertTrue(message = "applicationIds must be unique")
    @get:JsonIgnore
    val isSelectionUnique: Boolean get() = applicationIds == null || applicationIds.distinct().size == applicationIds.size
}

data class AdminGroupMessageSource(
    @field:Valid val scope: AdminGroupMessageScope,
    val channel: AdminGroupMessageChannel,
    @field:Size(min = 1, max = 1000) val body: String? = null,
    @field:Size(min = 1, max = 200) val templateId: String? = null,
    val fallbackToSms: Boolean = false,
) : StrictAdminGroupMessageRequest() {
    @get:AssertTrue(message = "SMS requires body and KAKAO requires templateId; fallbackToSms is only valid for KAKAO")
    @get:JsonIgnore
    val isContentValid: Boolean
        get() =
            (!fallbackToSms || channel == AdminGroupMessageChannel.KAKAO) &&
                when (channel) {
                    AdminGroupMessageChannel.SMS -> !body.isNullOrBlank() && templateId == null
                    AdminGroupMessageChannel.KAKAO -> body == null && !templateId.isNullOrBlank()
                }
}

data class CreateAdminGroupMessagePreviewRequest(
    val requestId: UUID,
    @field:Valid val source: AdminGroupMessageSource,
) : StrictAdminGroupMessageRequest()

data class ExecuteAdminGroupMessageRequest(
    @field:Valid val source: AdminGroupMessageSource,
    @field:Pattern(regexp = "^[a-f0-9]{64}$") val targetFingerprint: String,
    val idempotencyKey: UUID,
) : StrictAdminGroupMessageRequest()

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdminGroupMessageTemplateVariables(
    val expoName: String?,
    val name: String?,
    val programName: String?,
    val sessionName: String?,
    val location: String?,
    val status: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdminGroupMessageTarget(
    val targetId: String,
    val applicationId: UUID,
    val participantId: Long,
    val programType: ProgramSessionType,
    val recipientPhone: String? = null,
    val content: String? = null,
    val templateVariables: AdminGroupMessageTemplateVariables? = null,
    val missingReason: String? = null,
)

data class AdminGroupMessageResolvedTargets(
    val fingerprint: String,
    val targets: List<AdminGroupMessageTarget>,
    val applicationCount: Int,
)

data class AdminGroupMessageConfig(
    val enabled: Boolean,
    val channels: Map<AdminGroupMessageChannel, AdminGroupMessageChannelConfig>,
    val smsFallbackDefault: Boolean,
)

data class AdminGroupMessageChannelConfig(
    val enabled: Boolean,
    val templates: List<AdminGroupMessageTemplate> = emptyList(),
)

data class AdminGroupMessageTemplate(
    val id: String,
    val name: String,
    val content: String,
    val variableMapping: AdminGroupMessageTemplateVariableMapping,
    val variables: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdminGroupMessageTemplateVariableMapping(
    val expoName: String? = null,
    val name: String? = null,
    val programName: String? = null,
    val sessionName: String? = null,
    val location: String? = null,
    val status: String? = null,
)

data class AdminGroupMessagePreview(
    val previewId: UUID,
    val targetFingerprint: String,
    val applicationCount: Int,
    val recipientCount: Int,
    val messageCount: Int,
    val targetCount: Int,
    val sendableCount: Int,
    val missingCount: Int,
    val expiresAt: Instant,
    val items: List<AdminGroupMessagePreviewItem>,
)

data class AdminGroupMessagePreviewItem(
    val targetId: String,
    val applicationId: UUID,
    val participantId: Long?,
    val recipientMasked: String?,
    val content: String?,
    val missingReason: String?,
)

enum class AdminGroupMessageJobStatus { QUEUED, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_FAILURES, ATTENTION }

enum class AdminGroupMessageItemStatus { MISSING, PENDING, SENDING, ACCEPTED, DELIVERED, FAILED, UNKNOWN }

data class AdminGroupMessageJob(
    val jobId: String,
    val previewId: UUID,
    val status: AdminGroupMessageJobStatus,
    val createdAt: Instant,
    val counts: AdminGroupMessageJobCounts,
    val items: List<AdminGroupMessageJobItem>,
)

data class AdminGroupMessageJobCounts(
    val total: Int,
    val pending: Int,
    val sending: Int,
    val accepted: Int,
    val delivered: Int,
    val failed: Int,
    val unknown: Int,
    val missing: Int,
)

data class AdminGroupMessageJobItem(
    val targetId: String,
    val applicationId: UUID,
    val participantId: Long?,
    val recipientMasked: String?,
    val status: AdminGroupMessageItemStatus,
    val providerCode: String?,
    val failureReason: String?,
    val missingReason: String?,
)
