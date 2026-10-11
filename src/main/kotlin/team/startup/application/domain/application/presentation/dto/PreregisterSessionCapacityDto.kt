package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PreregisterSessionCapacityQuery(
    val expoId: UUID,
    val sessionId: Long,
)

@Schema(description = "사전등록 회차의 화면 안내용 잔여석")
data class PreregisterSessionCapacityResponse(
    @field:Schema(description = "회차 ID", example = "1") val sessionId: Long,
    @field:Schema(description = "현재 남은 좌석 수", example = "12") val remaining: Int,
    @field:Schema(description = "한 번에 신청할 수 있는 최대 인원", example = "5") val maxApplicants: Int,
)
