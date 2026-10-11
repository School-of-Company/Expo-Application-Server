package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "최대 500명의 연수자 신청 조회", example = """{"traineeIds":[456]}""")
data class TrainingApplicationsByTraineesRequest(
    @field:Size(max = 500) val traineeIds: List<Long>,
)

@Schema(description = "보고서용 연수 신청 참조", example = """{"applicationId":123,"traineeId":456,"trainingProgramId":1}""")
data class TrainingApplicationForReportResponse(
    val applicationId: Long,
    val traineeId: Long,
    val trainingProgramId: Long,
)
