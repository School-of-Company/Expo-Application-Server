package team.startup.application.domain.application.presentation.dto

import java.util.UUID

data class PreregisterReservationKey(
    val expoId: UUID,
    val requestId: String,
)

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
