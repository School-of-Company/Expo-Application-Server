package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant
import java.util.UUID

enum class ProgramSessionType { STANDARD, TRAINING }

@Schema(description = "프로그램 신청·취소 정책과 폼 참조")
data class ProgramSessionPolicy(
    val formId: UUID,
    val allowedOccupations: Set<String>,
    @field:JsonDeserialize(using = AdmissionCapacityDeserializer::class)
    val capacity: Int,
    val waitingCapacity: Int,
    val applicationStartedAt: Instant,
    val applicationEndedAt: Instant,
    val closed: Boolean,
    val cancelEndedAt: Instant,
    val promotionEndedAt: Instant,
    val groupId: UUID? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val channels: List<ProgramSessionChannel>? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonDeserialize(using = AdmissionCapacityDeserializer::class)
    val preRegistrationCapacity: Int? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonDeserialize(using = AdmissionCapacityDeserializer::class)
    val onsiteCapacity: Int? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val guardianPolicy: ProgramGuardianPolicy? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val onsiteStartedAt: Instant? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val onsiteEndedAt: Instant? = null,
) {
    fun hasAdmissionCapacities(): Boolean = preRegistrationCapacity != null || onsiteCapacity != null

    /** 접수 경로의 신청 기간. 현장 기간이 설정되지 않았으면 null이며 사전 모집 기간으로 대신 열지 않는다. */
    fun admissionWindow(onsite: Boolean): Pair<Instant, Instant>? =
        if (onsite) {
            onsiteStartedAt?.let { start -> onsiteEndedAt?.let { start to it } }
        } else {
            applicationStartedAt to applicationEndedAt
        }

    fun admissionCapacitiesValid(): Boolean =
        if ((onsiteStartedAt == null) != (onsiteEndedAt == null) ||
            (onsiteStartedAt != null && !onsiteStartedAt.isBefore(onsiteEndedAt))
        ) {
            false
        } else if (!hasAdmissionCapacities()) {
            true
        } else {
            capacity > 0 && waitingCapacity >= 0 && preRegistrationCapacity != null && onsiteCapacity != null &&
                preRegistrationCapacity >= 0 && onsiteCapacity >= 0 && channels == null &&
                preRegistrationCapacity.toLong() + onsiteCapacity.toLong() == capacity.toLong()
        }

    fun channelOf(occupation: String): ProgramSessionChannel? = channels?.firstOrNull { occupation in it.allowedOccupations }

    fun channelsValid(): Boolean {
        val channels = channels ?: return true
        val occupations = channels.flatMap { it.allowedOccupations }
        val names = channels.map { it.name.trim() }
        return channels.isNotEmpty() && names.none { it.isEmpty() || it.length > 50 } && names.toSet().size == names.size &&
            channels.none { it.capacity <= 0 || it.waitingCapacity < 0 || it.allowedOccupations.isEmpty() } &&
            occupations.size == occupations.toSet().size && occupations.toSet() == allowedOccupations &&
            channels.sumOf { it.capacity.toLong() } == capacity.toLong() &&
            channels.sumOf { it.waitingCapacity.toLong() } == waitingCapacity.toLong()
    }

    // Channel names and occupations decide where existing applications are counted.
    fun channelLayout(): Set<Pair<String, Set<String>>>? = channels?.map { it.name to it.allowedOccupations }?.toSet()
}

@Schema(description = "과거 정의 스냅샷의 직업별 모집 채널. 현재 Expo 정의는 제공하지 않으며 사전·현장 접수 경로와 별개")
data class ProgramSessionChannel(
    val name: String,
    val allowedOccupations: Set<String>,
    val capacity: Int,
    val waitingCapacity: Int,
)

@Schema(description = "프로그램 회차 전체 정의")
data class ProgramSessionDefinition(
    val expoId: String,
    val programType: ProgramSessionType,
    val id: Long,
    val groupId: UUID,
    val revision: Long,
    @field:Valid
    val policy: ProgramSessionPolicy?,
    val pendingRevision: Long? = null,
    val title: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val category: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "회차 장소. 미입력은 null이며 행사·사전등록 장소로 대체하지 않음")
    val place: String? = null,
)

internal fun ProgramSessionDefinition.hasValidPlace(): Boolean =
    place == null || (place.isNotBlank() && place == place.trim() && place.codePointCount(0, place.length) <= 255)

@Schema(description = "프로그램 회차 신청 요청")
data class ApplyProgramSessionRequest(
    @field:Positive
    @field:Schema(description = "신청 참가자 ID", example = "123")
    val participantId: Long,
    @field:Positive
    @field:Schema(description = "제출에 사용한 프로그램 폼 버전", example = "1")
    val formVersion: Long,
    @field:Schema(description = "프로그램 신청 답변")
    val answers: JsonNode,
)

data class ApplyProgramSessionCommand(
    val expoId: String,
    val programType: ProgramSessionType,
    val programId: Long,
    val participantId: Long,
    val formVersion: Long,
    val answers: JsonNode,
    val sessionId: String,
    val idempotencyKey: String,
    val onsite: Boolean = false,
) {
    override fun toString() = "ApplyProgramSessionCommand(expoId=$expoId, programId=$programId, participantId=$participantId)"
}

@Schema(description = "프로그램 회차 신청 접수 결과")
data class ProgramSessionReceipt(
    @field:Schema(description = "신청 ID", example = "0199c000-0000-7000-8000-000000000001") val applicationId: UUID,
    @field:Schema(description = "프로그램 ID", example = "1") val programId: Long,
    @field:Schema(description = "참가자 ID", example = "123") val participantId: Long,
    @field:Schema(description = "연수자 ID. 일반 신청에서는 null", example = "456") val traineeId: Long?,
    @field:Schema(description = "접수 상태", example = "CONFIRMED") val status: String = "CONFIRMED",
)

data class ProgramFormSnapshot(
    val expoId: String,
    val id: UUID,
    val purpose: String,
    val version: Long,
    val startedAt: Instant,
    val endedAt: Instant,
    val questions: JsonNode,
)

@Schema(description = "프로그램 변경 전후 정의. 삭제 시 definition은 null")
data class ProgramMutation(
    @field:Valid
    val previous: ProgramSessionDefinition?,
    @field:Valid
    val definition: ProgramSessionDefinition?,
)

@Schema(description = "원자적으로 승인할 프로그램 변경 목록. 1–1000건")
data class ProgramChangePayload(
    @field:Valid
    @field:Size(min = 1, max = 1000)
    val mutations: List<ProgramMutation>,
)

data class PrepareProgramChangeCommand(
    val expoId: UUID,
    val changeId: UUID,
    val payload: ProgramChangePayload,
)

data class ProgramSessionKey(
    val expoId: UUID,
    val programType: ProgramSessionType,
    val programId: Long,
)

data class CancelProgramSessionCommand(
    val applicationId: UUID,
    val actorId: Long? = null,
)
