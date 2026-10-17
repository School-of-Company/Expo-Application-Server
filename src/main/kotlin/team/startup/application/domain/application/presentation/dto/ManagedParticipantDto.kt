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

enum class ProgramIneligibleReason { OCCUPATION_NOT_ALLOWED, PREREGISTRATION_NOT_CONFIRMED, GROUP_ALREADY_APPLIED, TIME_CONFLICT }

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
