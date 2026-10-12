package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "사전등록 인원 집계. 확정 인원만 현재 고유 인원이며 취소 인원과 중복될 수 있습니다. 대기는 현행 정책상 0명입니다.")
data class AdminPreregisterSummary(
    val expoId: UUID,
    val confirmedUniqueParticipants: Long,
    val cancelledUniqueParticipants: Long,
    val waitingParticipants: Long,
    val pendingReservations: Long,
    val pendingApplicants: Long,
    val sessions: List<AdminPreregisterSessionSummary>,
)

@Schema(description = "회차별 확정·취소 고유 참가자와 User 확정 전 예약 건수·좌석 수")
data class AdminPreregisterSessionSummary(
    val sessionId: Long,
    val confirmedParticipants: Long,
    val cancelledParticipants: Long,
    val waitingParticipants: Long,
    val pendingReservations: Long,
    val pendingApplicants: Long,
)
