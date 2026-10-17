package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// Kafka `application.promotion-offer.requested` v1. Separate from `application.program-session.promoted`: an offer
// asks the representative to respond and never means the application is confirmed.
data class PromotionOfferEvent(
    @field:Min(1) @field:Max(1)
    val version: Int,
    val eventId: UUID,
    val offerId: UUID,
    val expoId: UUID,
)

@Schema(description = "발송 직전 참여 제안 확인. eligible=false면 보내지 않습니다.")
data class PromotionOfferCheck(
    val eligible: Boolean,
    @field:Schema(description = "NOT_CURRENT_OFFER 또는 OUTSIDE_SEND_WINDOW") val reason: String? = null,
    val eventId: UUID? = null,
    val offerId: UUID? = null,
    val expoId: UUID? = null,
    @field:Schema(description = "PROGRAM 또는 PREREGISTER") val target: String? = null,
    val representativePhoneNumber: String? = null,
    @field:Schema(description = "수신 대표자 이름") val recipientName: String? = null,
    @field:Schema(description = "프로그램명 또는 사전등록 회차명") val title: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val location: String? = null,
    @field:Schema(description = "확보 좌석 수(학생·보호자 묶음·일행 전체)") val seats: Int? = null,
    @field:Schema(description = "인증된 대표자가 수락·거절하는 Client 화면") val responseUrl: String? = null,
    @field:Schema(description = "기존 신청·승급·폼 마감 중 가장 이른 시각. 응답 기한은 공급자 접수+6시간과 이 값 중 이른 값") val hardDeadline: Instant? = null,
    @field:Schema(description = "이번 발송 시간대 종료(18:00 KST, 미포함). 이 시각 이후에는 보내지 않습니다") val sendWindowEndsAt: Instant? = null,
    val responseHours: Int? = null,
) {
    override fun toString() = "PromotionOfferCheck(eligible=$eligible, reason=$reason, offerId=$offerId)"
}

enum class PromotionOfferDeliveryStatus { ACCEPTED, DELIVERED, FAILED, UNKNOWN, SKIPPED }

@Schema(description = "Notification의 참여 제안 발송 결과. ACCEPTED는 공급자가 실제로 접수한 시각을 포함해야 합니다.")
data class PromotionOfferDeliveryRequest(
    @field:NotNull val eventId: UUID?,
    @field:NotNull val status: PromotionOfferDeliveryStatus?,
    val attemptId: UUID? = null,
    @field:Schema(description = "공급자 요청 직전 시각") val attemptedAt: Instant? = null,
    @field:Schema(description = "공급자 접수 성공 응답을 받은 시각. attemptedAt과 다른 실제 값만 보냅니다") val providerAcceptedAt: Instant? = null,
    @field:Size(max = 100) val reason: String? = null,
)

data class PromotionOfferDeliveryCommand(
    val offerId: UUID,
    val request: PromotionOfferDeliveryRequest,
)

@Schema(description = "발송 결과 반영 뒤 제안 상태")
data class PromotionOfferDeliveryResult(
    val offerId: UUID,
    val state: String,
)

data class OwnerPromotionOfferCommand(
    val expoId: UUID,
    val participantSession: String,
    val offerId: UUID? = null,
)

@Schema(description = "대표자에게 보이는 참여 제안. 수락해야 확정되며 문자 발송만으로 확정되지 않습니다.")
data class OwnerPromotionOffer(
    val offerId: UUID,
    @field:Schema(description = "PROGRAM 또는 PREREGISTER") val target: String,
    @field:Schema(description = "PENDING(안내 준비·전달 확인 중), OPEN(응답 가능), ACCEPTED, DECLINED, EXPIRED, ENDED(마감·전달 불가 등으로 종료)")
    val status: String,
    val respondable: Boolean,
    @field:Schema(description = "응답 기한. 공급자 접수 전에는 null") val expiresAt: Instant?,
    val programType: ProgramSessionType?,
    val programId: Long?,
    val sessionId: Long?,
    val title: String?,
    val seats: Int,
)
