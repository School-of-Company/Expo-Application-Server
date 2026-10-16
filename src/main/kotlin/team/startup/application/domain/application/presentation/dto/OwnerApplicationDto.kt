package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.util.UUID

data class GetOwnerApplicationsCommand(
    val expoId: UUID,
    val sessionId: String,
) {
    override fun toString() = "GetOwnerApplicationsCommand(expoId=$expoId)"
}

enum class OwnerApplicationType { PREREGISTER, STANDARD, TRAINING }

data class CancelOwnerApplicationCommand(
    val expoId: UUID,
    val sessionId: String,
    val type: OwnerApplicationType,
    val applicationId: String,
) {
    override fun toString() = "CancelOwnerApplicationCommand(expoId=$expoId, type=$type, applicationId=$applicationId)"
}

@Schema(description = "세션 소유자가 관리하는 참가자")
data class ManagedOwnerParticipant(
    @field:Schema(description = "참가자 ID", example = "123") val participantId: Long,
    @field:Schema(description = "참가자 이름", example = "홍길동") val name: String,
    @field:Schema(description = "직업", example = "TEACHER") val occupation: String?,
    @field:Schema(description = "대표자 여부", example = "true") val representative: Boolean,
)

data class OwnerApplicationParty(
    val participants: List<ManagedOwnerParticipant>,
) {
    val representativeId: Long? get() = participants.singleOrNull { it.representative }?.participantId
}

data class OwnerApplicationHistory(
    @field:Schema(description = "이력 상태", example = "CANCELLED") val status: String,
)

@Schema(description = "내 신청 한 건과 취소 가능 상태")
data class OwnerApplicationItem(
    @field:Schema(description = "신청 ID. 신청 종류에 따라 숫자 문자열 또는 UUID", example = "123") val applicationId: String,
    @field:Schema(description = "신청 종류", example = "PREREGISTER") val type: OwnerApplicationType,
    @field:Schema(description = "참가자 ID", example = "123") val participantId: Long,
    @field:Schema(description = "사전등록 회차 또는 프로그램 ID", example = "1") val sessionOrProgramId: Long,
    @field:Schema(description = "신청 상태", example = "CONFIRMED") val status: String,
    @field:Schema(description = "현재 신청 여부", example = "true") val applied: Boolean,
    @field:Schema(description = "접수 시점의 문항 스냅샷") val questions: JsonNode?,
    @field:Schema(description = "접수 시점의 답변 스냅샷") val answers: JsonNode?,
    @field:Schema(description = "신청 상태 이력") val history: List<OwnerApplicationHistory>,
    @field:Schema(description = "현재 취소 가능 여부", example = "false") val cancellable: Boolean = false,
    @field:Schema(description = "취소 불가 사유", example = "EXTERNAL_CONTRACT_REQUIRED")
    val cancellationBlockedReason: String = "EXTERNAL_CONTRACT_REQUIRED",
    @field:Schema(description = "현재 프로그램 회차 장소. 미입력·삭제된 회차·사전등록은 null")
    val place: String? = null,
    @field:Schema(description = "신청 당시 프로그램 회차 장소. 기존 스냅샷에 없거나 사전등록이면 null")
    val snapshotPlace: String? = null,
)

@Schema(description = "인증된 대표자의 참가자와 신청 목록")
data class OwnerApplicationsResponse(
    @field:Schema(description = "관리 가능한 일행") val participants: List<ManagedOwnerParticipant>,
    @field:Schema(description = "일행의 신청 목록") val applications: List<OwnerApplicationItem>,
)
