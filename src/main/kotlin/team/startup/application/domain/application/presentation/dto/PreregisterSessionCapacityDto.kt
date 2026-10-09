package team.startup.application.domain.application.presentation.dto

import java.util.UUID

data class PreregisterSessionCapacityQuery(
    val expoId: UUID,
    val sessionId: Long,
)

data class PreregisterSessionCapacityResponse(
    val sessionId: Long,
    val remaining: Int,
    val waitingRemaining: Int,
    val maxApplicants: Int,
)
