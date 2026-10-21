package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ManagedParticipantCommand(
    val expoId: UUID,
    val sessionId: String,
) {
    override fun toString() = "ManagedParticipantCommand(expoId=$expoId)"
}

data class ProgramParticipantsCommand(
    val owner: ManagedParticipantCommand,
    val type: ProgramSessionType,
    val programId: Long,
    val onsite: Boolean = false,
)

data class ProgramParticipantItem(
    val participant: ManagedOwnerParticipant,
    val eligible: Boolean,
    val ineligibleReason: ProgramIneligibleReason? = null,
)

// SESSION_TIME_MISMATCH(#197): 확정 사전등록은 있지만 그 회차 시간이 프로그램 시간과 겹치지 않는다.
// 보호자 동반 정책 거절(#271): GUARDIAN_REQUIRED(초등 1~3학년 단독), GUARDIAN_LINK_INVALID(학생·보호자 연결·역할·단독 보호자),
// GRADE_INVALID(학년 없음·알 수 없음·4학년 이상 연결), SCHOOL_TYPE_MISMATCH(학교급 답변이 직업·역할과 다름).
enum class ProgramIneligibleReason {
    OCCUPATION_NOT_ALLOWED,
    PREREGISTRATION_NOT_CONFIRMED,
    SESSION_TIME_MISMATCH,
    GROUP_ALREADY_APPLIED,
    TIME_CONFLICT,
    GUARDIAN_REQUIRED,
    GUARDIAN_LINK_INVALID,
    GRADE_INVALID,
    SCHOOL_TYPE_MISMATCH,
}

data class ProgramParticipantsResponse(
    val registered: Boolean,
    val participants: List<ProgramParticipantItem>,
    val capacity: ProgramCapacityInfo? = null,
    val channels: List<ProgramChannelCapacityInfo> = emptyList(),
    val admissionSeats: ProgramAdmissionSeats? = null,
)

data class ProgramAdmissionSeats(
    val preRegistration: ProgramAdmissionCapacity,
    val onsite: ProgramAdmissionCapacity,
    val unknownConfirmedCount: Long,
    @field:Schema(description = "사전 좌석이 없을 때 사전 대기를 받는 회차인지(대기 정원 > 0). 현장 접수는 대기가 없다")
    val waitingEnabled: Boolean = false,
    @field:Schema(description = "현장 정원이 있어 현장 신청 진입점을 쓸 수 있는 회차인지. 개방 여부는 신청 시 프로그램 폼 기간으로 판정한다")
    val onsiteAdmissionEnabled: Boolean = false,
    @field:Schema(description = "참여 제안(#144)으로 임시 확보된 사전 좌석 수. 사전 잔여석에서 이미 뺐다")
    val offeredSeats: Long = 0,
)

data class ProgramAdmissionCapacity(
    val capacity: Int,
    val confirmedCount: Long,
    val remaining: Long,
)

data class ProgramCapacityInfo(
    val capacity: Int,
    val confirmedCount: Long,
    @field:Schema(description = "정원에서 확정 인원을 뺀 물리적 빈자리. FIFO·채널 제한 때문에 즉시 확정을 보장하지 않는 참고값")
    val remaining: Long,
    val waitingCapacity: Int,
    @field:Schema(description = "현재 WAITING 원장 신청 수") val waitingCount: Int,
    @field:Schema(description = "대기 정원에서 현재 WAITING 신청 수를 뺀 남은 대기석") val waitingRemaining: Int,
)

data class ProgramChannelCapacityInfo(
    val name: String,
    val allowedOccupations: Set<String>,
    val capacity: ProgramCapacityInfo,
)

data class ParticipantQrCommand(
    val owner: ManagedParticipantCommand,
    val participantId: Long,
)

data class ParticipantQrResponse(
    val participantId: Long,
    val code: String,
) {
    override fun toString() = "ParticipantQrResponse(participantId=$participantId)"
}
