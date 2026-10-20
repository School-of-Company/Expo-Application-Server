package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
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
    // User가 저장한 박람회 현장 등록(FIELD) 여부. 현장 접수 자격 판정에만 쓰고 응답에는 싣지 않는다
    @get:JsonIgnore val fieldRegistered: Boolean = false,
    // User가 저장한 소속(학교). 동행자 중복 판정(#225)에만 쓴다. 옛 User 응답에는 없어 schoolReported가 false다
    @get:JsonIgnore val school: String? = null,
    @get:JsonIgnore val schoolReported: Boolean = false,
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
    @field:Schema(description = "대표자 본인의 확정 사전등록에 동행자를 더 추가할 수 있는지(#169). 다른 신청은 false", example = "true")
    val companionAddable: Boolean = false,
    @field:Schema(description = "지금 더 추가할 수 있는 동행자 수. 동행자 상한과 회차 잔여석 중 작은 값이며 추가할 수 없으면 0", example = "2")
    val remainingCompanionSlots: Int = 0,
    @field:Schema(
        description =
            "동행자 추가 불가 사유. 가능하면 빈 문자열. NOT_REPRESENTATIVE_PREREGISTRATION, APPLICATION_STATE_CHANGED, " +
                "SESSION_CHANGE_PENDING, SESSION_CLOSED, SESSION_STARTED, REGISTRATION_PERIOD_ENDED, COMPANION_NOT_ALLOWED, " +
                "SNAPSHOT_UNAVAILABLE, ADDITION_IN_PROGRESS, COMPANION_LIMIT_REACHED, SEATS_UNAVAILABLE, EXTERNAL_CONTRACT_REQUIRED",
        example = "NOT_REPRESENTATIVE_PREREGISTRATION",
    )
    val companionAddBlockedReason: String = "NOT_REPRESENTATIVE_PREREGISTRATION",
)

@Schema(description = "확정된 사전등록 대표자 일행에 더할 동행자")
data class AddOwnerPreregisterCompanionsRequest(
    @field:NotNull
    @field:Size(min = 1, max = 4)
    @field:Schema(
        description = "새 동행자 1~4명. 사전등록 동행자 답변과 같은 형식: name(1~10자), occupation, region, 교사·예비교사만 school(1~100자)",
    )
    val companions: List<JsonNode>?,
)

data class AddOwnerPreregisterCompanionsCommand(
    val expoId: UUID,
    val sessionId: String,
    val applicationId: String,
    val companions: List<JsonNode>,
    val idempotencyKey: String?,
) {
    override fun toString() =
        "AddOwnerPreregisterCompanionsCommand(expoId=$expoId, applicationId=$applicationId, companions=${companions.size})"
}

@Schema(description = "동행자 추가 결과")
data class OwnerPreregisterCompanionsResult(
    @field:Schema(description = "동행자를 더한 대표자 사전등록 ID", example = "123") val applicationId: String,
    @field:Schema(description = "새로 확정된 동행자 참가자 ID. QR은 대표자 번호로 발송된다. 대기·처리 중이면 빈 목록") val participantIds: List<Long>,
    @field:Schema(
        description = "CONFIRMED(확정), WAITING(자리가 모자라 회차 대기열에 섬, #144 규칙으로 승급), PROCESSING(승급 뒤 User 저장 중)",
        example = "CONFIRMED",
    )
    val status: String = "CONFIRMED",
    @field:Schema(description = "WAITING일 때 회차 대기 순번(1부터). 그 외 null", example = "2")
    val position: Int? = null,
)

data class AddOwnerPreregisterSessionRequest(
    @field:NotNull
    @field:Positive
    @field:Schema(description = "추가할 사전등록 회차 ID", example = "12")
    val sessionId: Long?,
    @field:NotNull
    @field:Size(min = 1, max = 5)
    @field:Schema(description = "이 회차에 갈 일행 참가자 ID 1~5명. 대표자는 빠져도 되지만 학생만으로는 안 된다", example = "[101, 102]")
    val participantIds: List<Long>?,
)

data class AddOwnerPreregisterSessionCommand(
    val expoId: UUID,
    val sessionId: String,
    val preregisterSessionId: Long,
    val participantIds: List<Long>,
    val idempotencyKey: String?,
) {
    override fun toString() =
        "AddOwnerPreregisterSessionCommand(expoId=$expoId, preregisterSessionId=$preregisterSessionId, participantIds=$participantIds)"
}

@Schema(description = "다른 회차 추가 결과")
data class OwnerPreregisterSessionResult(
    @field:Schema(description = "추가한 회차 ID", example = "12") val sessionId: Long,
    @field:Schema(description = "확정된 참석자 ID. QR은 그대로다. 대기·처리 중이면 빈 목록") val participantIds: List<Long>,
    @field:Schema(description = "CONFIRMED(확정), WAITING(회차 대기열에 섬, #144 규칙으로 승급), PROCESSING(승급 뒤 확정 중)", example = "CONFIRMED")
    val status: String,
    @field:Schema(description = "WAITING일 때 회차 대기 순번(1부터). 그 외 null", example = "2")
    val position: Int? = null,
)

@Schema(description = "인증된 대표자의 참가자와 신청 목록")
data class OwnerApplicationsResponse(
    @field:Schema(description = "관리 가능한 일행") val participants: List<ManagedOwnerParticipant>,
    @field:Schema(description = "일행의 신청 목록") val applications: List<OwnerApplicationItem>,
    @field:Schema(
        description =
            "개인 QR 조회(GET /participants/{participantId}/qr) 대상 참가자 ID. 확정 사전등록 또는 사전등록 이력 없는 현장등록 참가자이며 " +
                "신청이 없어도 들어간다. Attendance 연동이 꺼져 있으면 비어 있다(#250)",
    )
    val qrParticipantIds: List<Long> = emptyList(),
)
