package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PreregisterReservationKey(
    val expoId: UUID,
    val requestId: String,
)

@Schema(description = "민감한 폼·전화번호를 제외한 예약 복구 상태")
data class PreregisterReservationStatus(
    val requestId: String,
    val sessionId: Long,
    val state: String,
    val applicants: Int,
    val userRequestId: String?,
    val participantIds: List<Long>,
    val errorStatus: Int?,
    val attempts: Int,
)
