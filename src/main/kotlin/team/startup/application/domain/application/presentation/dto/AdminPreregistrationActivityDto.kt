package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "CONFIRMED는 확정 전이, CANCELLED는 확정 취소 전이, WAITING_WITHDRAWN은 대기 일행 철회")
enum class AdminPreregistrationAction { CONFIRMED, CANCELLED, WAITING_WITHDRAWN }

data class AdminPreregistrationActivityQuery(
    @field:Schema(description = "처리 유형")
    val action: AdminPreregistrationAction? = null,
    @field:Positive val sessionId: Long? = null,
    @field:Schema(description = "처리 시각 하한(포함). 시각 미기록 행은 기간 조건에 걸리지 않음")
    val from: Instant? = null,
    @field:Schema(description = "처리 시각 상한(미포함)")
    val until: Instant? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    @field:Schema(description = "확정·취소는 User 현재 이름·번호, 대기 철회는 제출 당시 대표자 이름·번호")
    @field:Size(min = 1, max = 50) val q: String? = null,
) {
    @get:AssertTrue(message = "q must not be blank")
    val isParticipantQueryValid: Boolean get() = q == null || q.isNotBlank()
}

data class AdminPreregistrationActivityReservation(
    @field:Schema(description = "대기 신청 당시 대표자 이름")
    val name: String?,
    @field:Schema(description = "대기 신청 당시 대표자 번호")
    val phoneNumber: String?,
)

data class AdminPreregistrationActivity(
    @field:Schema(description = "이력 행 key. TRANSITION:{id} 또는 RESERVATION:{expoId}:{requestId}")
    val key: String,
    @field:Schema(description = "접수대장 행 key. 목록·상세의 key와 같음")
    val ledgerKey: String,
    val action: AdminPreregistrationAction,
    val sessionId: Long,
    @field:Schema(description = "처리 대상 참가자 ID. 대기 철회는 User 미저장이라 null. 실행자가 아님")
    val participantId: Long?,
    @field:Schema(description = "대기 철회 대상 예약 requestId. 확정·취소는 null")
    val reservationRequestId: String?,
    @field:Schema(description = "처리 대상 인원. 확정·취소 전이는 1(참가자 단위), 대기 철회는 일행 인원")
    val applicants: Int,
    @field:Schema(description = "같은 신청의 앞선 전이 상태. 없으면 null(최초 전이 또는 앞선 기록 없음). 대기 철회는 WAITING")
    val previousStatus: AdminPreregistrationStatus?,
    val status: AdminPreregistrationStatus,
    @field:Schema(description = "처리 시각. 과거 행은 미기록(null)이며 추정하지 않음. 정렬은 시각 역순, 미기록은 맨 뒤")
    val createdAt: Instant?,
    @field:Schema(description = "UNKNOWN은 주체 미기록 과거 행 또는 인증 주체 없는 내부 호출")
    val actorType: ApplicationActorType,
    @field:Schema(description = "ADMIN 처리의 관리자 ID. 그 외는 null")
    val actorId: Long?,
    @field:Schema(description = "USER 처리의 실행 대표자. 과거 미기록·USER 외 주체는 null")
    val actorUser: ApplicationUserActor? = null,
    @field:Schema(description = "현재 User 참가자 정보. 처리 당시 정보가 아님. 대기 철회는 null")
    val participant: AdminParticipantDetail? = null,
    @field:Schema(description = "대기 철회의 제출 당시 대표자 정보. 확정·취소는 null")
    val reservation: AdminPreregistrationActivityReservation? = null,
)

data class AdminPreregistrationActivityPage(
    val items: List<AdminPreregistrationActivity>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)
