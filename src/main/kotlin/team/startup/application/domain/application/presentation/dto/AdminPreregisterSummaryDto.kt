package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "사전등록 인원 집계. 확정 인원만 현재 고유 인원이며 취소 인원과 중복될 수 있습니다. 대기는 User 저장 전 일행 인원 합입니다.")
data class AdminPreregisterSummary(
    val expoId: UUID,
    val confirmedUniqueParticipants: Long,
    val cancelledUniqueParticipants: Long,
    val waitingParticipants: Long,
    val pendingReservations: Long,
    val pendingApplicants: Long,
    val sessions: List<AdminPreregisterSessionSummary>,
)

@Schema(description = "회차별 확정·취소 고유 참가자, User 확정 전 예약 건수·좌석 수와 대기 상태")
data class AdminPreregisterSessionSummary(
    val sessionId: Long,
    val confirmedParticipants: Long,
    val cancelledParticipants: Long,
    val waitingParticipants: Long,
    val pendingReservations: Long,
    val pendingApplicants: Long,
    @field:Schema(description = "대기 일행 수") val waitingReservations: Long = 0,
    @field:Schema(description = "대기 선두 일행 인원. 이 값이 빈 확정석보다 크면 엄격 FIFO로 승급이 멈춥니다.") val waitingHeadApplicants: Int? = null,
    @field:Schema(description = "저장된 정원 기준 빈 확정석") val freeConfirmedSeats: Long? = null,
    @field:Schema(description = "Application이 적용한 대기 정원. null이면 미적용") val appliedWaitingCapacity: Int? = null,
    @field:Schema(
        description = "대기가 있는데 승급하지 않는 저장 기준 사유: CLOSED, SESSION_STARTED, HEAD_TOO_LARGE. 신청 기간 종료는 Form 기준이라 포함하지 않음",
    )
    val promotionBlockedReason: String? = null,
)
