package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema

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

@Schema(description = "일반 프로그램 신청 결과", example = """{"applicationId":123,"participantId":123}""")
data class StandardApplicationResponse(
    val applicationId: Long,
    val participantId: Long,
)
