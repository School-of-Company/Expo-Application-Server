package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.CodePointLength
import java.time.Instant
import java.util.UUID

@Schema(description = "사전등록 회차 전체 정의")
data class PreregisterSessionDefinition(
    @field:Positive val id: Long,
    val expoId: UUID,
    @field:NotBlank @field:CodePointLength(max = 50) val title: String,
    val startedAt: Instant,
    val endedAt: Instant,
    @field:NotBlank val place: String,
    @field:Positive val capacity: Int,
    @param:JsonProperty(required = true) val closed: Boolean,
    @field:Positive val revision: Long,
    val cancelEndedAt: Instant? = null,
    @field:Schema(description = "회차별 대기 정원. 0이면 대기 미사용, null이면 Application에 적용되지 않음")
    @field:jakarta.validation.constraints.PositiveOrZero
    val waitingCapacity: Int? = null,
    @field:Schema(description = "교사·예비교사 정원(Expo #144). nonTeacherCapacity와 함께 null이면 구분 없는 기존 회차")
    @field:jakarta.validation.constraints.PositiveOrZero
    val teacherCapacity: Int? = null,
    @field:Schema(description = "교사·예비교사 외 대상 정원. teacherCapacity와 함께 있으며 합은 capacity")
    @field:jakarta.validation.constraints.PositiveOrZero
    val nonTeacherCapacity: Int? = null,
)

// #173: sessions with target capacities limit teacher and non-teacher seats separately.
internal val PreregisterSessionDefinition.targeted: Boolean get() = teacherCapacity != null

// Definitions stored before #99 have no waitingCapacity. Null means not adopted, so compare without it.
internal fun PreregisterSessionDefinition.sameAs(other: PreregisterSessionDefinition?): Boolean =
    other != null && this == if (waitingCapacity == null) other.copy(waitingCapacity = null) else other

internal fun PreregisterSessionChangeRequest.sameAs(other: PreregisterSessionChangeRequest): Boolean =
    copy(definition = null) == other.copy(definition = null) &&
        (definition == other.definition || definition?.sameAs(other.definition) == true)

enum class PreregisterChangeOperation { UPDATE, DELETE }

@Schema(description = "회차 변경 승인 요청. 삭제 시 definition은 null")
data class PreregisterSessionChangeRequest(
    val operation: PreregisterChangeOperation,
    @param:JsonProperty(required = true) val definitionChanged: Boolean,
    @field:Valid val definition: PreregisterSessionDefinition?,
)

data class PreparePreregisterSessionChangeCommand(
    val expoId: UUID,
    val sessionId: Long,
    val nextRevision: Long,
    val request: PreregisterSessionChangeRequest,
)

// Participant IDs and representative ownership must be resolved by a trusted upstream, not from form answers.
data class ApplyPreregisterSessionCommand(
    val expoId: UUID,
    val sessionId: Long,
    val requestId: String,
    val representativeId: Long,
    val participantIds: List<Long>,
)

@Schema(description = "사전등록 접수 상태. 확정은 201 무본문이고, 대기·처리 중만 202 본문으로 응답합니다.")
data class PreregisterRegistrationReceipt(
    @field:Schema(description = "CONFIRMED, WAITING 또는 PROCESSING", example = "WAITING") val status: String,
    @field:Schema(description = "회차 ID", example = "1") val sessionId: Long,
    @field:Schema(description = "대기 일행 순번(1부터). 대기가 아니면 null", example = "3") val position: Int?,
)

@Schema(description = "사전등록 회차 대기 정원 명시 채택. expected는 Expo의 현재 값과 같아야 합니다.")
data class AdoptPreregisterWaitingCapacityRequest(
    @field:jakarta.validation.constraints.PositiveOrZero val expected: Int,
)

data class AdoptPreregisterWaitingCapacityCommand(
    val expoId: UUID,
    val sessionId: Long,
    val expected: Int,
)

data class OwnerPreregisterWaitlistCommand(
    val expoId: UUID,
    val participantSession: String,
    // #198: a party may wait for several sessions. Null keeps the latest one.
    val sessionId: Long? = null,
)

@Schema(description = "참가자 세션의 검증 번호로 찾은 대기 일행. User 저장 전이므로 QR이 없습니다.")
data class OwnerPreregisterWaitlist(
    @field:Schema(description = "회차 ID", example = "1") val sessionId: Long,
    @field:Schema(description = "대표자를 포함한 일행 인원", example = "3") val applicants: Int,
    @field:Schema(description = "WAITING, 승급 뒤 User 저장 중인 PROCESSING, 회차 시작으로 승급이 끝난 PROMOTION_ENDED", example = "WAITING")
    val status: String,
    @field:Schema(description = "대기 일행 순번(1부터). WAITING이 아니면 null", example = "2") val position: Int?,
)

@Schema(description = "사전등록 신청 접수 결과", example = """{"id":123,"participantId":123,"sessionId":1,"status":"CONFIRMED"}""")
data class PreregisterApplicationReceipt(
    val id: Long,
    val participantId: Long,
    val sessionId: Long,
    val status: String,
)

data class CancelPreregisterSessionCommand(
    val expoId: UUID,
    val sessionId: Long,
    val representativeId: Long?,
    val applicationIds: List<Long>,
    // Verified admin executing the cancellation (#182); null keeps the internal UNKNOWN record.
    val adminId: Long? = null,
)

data class PreregisterSessionKey(
    val expoId: UUID,
    val sessionId: Long,
)

internal fun PreregisterSessionDefinition.isValidDefinition(): Boolean =
    id > 0 && revision > 0 && capacity > 0 &&
        title.isNotBlank() && title.codePointCount(0, title.length) <= 50 && place.isNotBlank() &&
        startedAt.isBefore(endedAt) && startedAt.nano % 1000 == 0 && endedAt.nano % 1000 == 0 &&
        (cancelEndedAt == null || cancelEndedAt.nano % 1000 == 0) && (waitingCapacity == null || waitingCapacity >= 0) &&
        targetCapacitiesValid()

// Expo #144: both target capacities are null, or both are non-negative and add up to capacity.
private fun PreregisterSessionDefinition.targetCapacitiesValid(): Boolean {
    val teacher = teacherCapacity ?: return nonTeacherCapacity == null
    val nonTeacher = nonTeacherCapacity ?: return false
    return teacher >= 0 && nonTeacher >= 0 && teacher.toLong() + nonTeacher == capacity.toLong()
}
