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
