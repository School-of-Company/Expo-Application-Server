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
    @field:Schema(description = "새 신청이 확정될 수 있는 좌석 수. 대기열이 있으면 0", example = "12") val remaining: Int,
    @field:Schema(description = "한 번에 확정 신청할 수 있는 최대 인원", example = "5") val maxApplicants: Int,
    @field:Schema(description = "확정 및 User 저장 중인 인원", example = "88") val confirmed: Long = 0,
    @field:Schema(description = "대기 일행 인원 합", example = "0") val waiting: Long = 0,
    @field:Schema(description = "Application이 적용한 대기 정원. 미적용이면 null", example = "10") val appliedWaitingCapacity: Int? = null,
    @field:Schema(description = "남은 대기석", example = "10") val waitingRemaining: Int = 0,
    @field:Schema(description = "한 번에 대기 신청할 수 있는 최대 인원. 대기 접수 중지 시 0", example = "5") val maxWaitingApplicants: Int = 0,
    @field:Schema(description = "ADOPTED 또는 신청 이력 후 Expo 값이 적용되지 않은 UNADOPTED", example = "ADOPTED")
    val waitingCapacityStatus: String = "ADOPTED",
    @field:Schema(description = "Expo의 현재 대기 정원. UNADOPTED일 때 채택 요청의 expected 값", example = "10")
    val expoWaitingCapacity: Int? = null,
)
