package team.startup.application.domain.application.presentation.dto

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
)

data class ProgramParticipantsResponse(
    val registered: Boolean,
    val participants: List<ProgramParticipantItem>,
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
