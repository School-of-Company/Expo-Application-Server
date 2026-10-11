package team.startup.application.domain.application.presentation.dto

data class ParticipantReference(
    val id: Long,
    val expoId: String,
)

data class StandardProgramReference(
    val id: Long,
    val expoId: String,
)

data class ApplyStandardProgramsCommand(
    val participant: ParticipantReference,
    val programs: List<StandardProgramReference>,
)

data class StandardApplicationResponse(
    val applicationId: Long,
    val participantId: Long,
    val status: Boolean,
    val entryTime: String?,
    val leaveTime: String?,
)
