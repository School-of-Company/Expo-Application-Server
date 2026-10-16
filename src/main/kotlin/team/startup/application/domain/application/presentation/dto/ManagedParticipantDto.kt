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
    val waitingEnabled: Boolean = false,
    val onsiteAdmissionEnabled: Boolean = false,
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
