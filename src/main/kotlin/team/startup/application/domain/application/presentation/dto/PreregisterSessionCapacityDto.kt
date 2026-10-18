package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PreregisterSessionCapacityQuery(
    val expoId: UUID,
    val sessionId: Long,
    // Optional PARTICIPANT_SESSION; only a verified owner of a confirmed application here sees its own category seats.
    val participantSession: String? = null,
) {
    override fun toString() = "PreregisterSessionCapacityQuery(expoId=$expoId, sessionId=$sessionId)"
}

@Schema(description = "대상별 정원 회차의 공개 좌석(#173). 교사·예비교사 인원·잔여 숫자는 싣지 않습니다.")
data class PreregisterTargetSeats(
    @field:Schema(description = "교사·예비교사 좌석이 1석 이상 남았는지. 혼합 일행의 수용 여부는 제출 시 판정합니다.") val teacherAvailable: Boolean,
    @field:Schema(description = "교사·예비교사 외 대상 잔여석", example = "120") val nonTeacherRemaining: Int,
    @field:Schema(description = "교사·예비교사 외 대상만으로 한 번에 신청할 수 있는 최대 인원", example = "5") val nonTeacherMaxApplicants: Int,
    @field:Schema(
        description = "인증된 대표자 일행에 이 회차 확정 교사·예비교사 신청이 있을 때만 교사·예비교사 잔여석. 그 외에는 null",
        example = "12",
    )
    val ownTeacherRemaining: Int? = null,
)

@Schema(description = "사전등록 회차의 화면 안내용 잔여석")
data class PreregisterSessionCapacityResponse(
    @field:Schema(description = "회차 ID", example = "1") val sessionId: Long,
    @field:Schema(description = "새 신청이 확정될 수 있는 좌석 수. 대기열이 있으면 0. 대상별 정원 회차는 교사 숫자가 드러나므로 null", example = "12")
    val remaining: Int?,
    @field:Schema(description = "한 번에 확정 신청할 수 있는 최대 인원. 대상별 정원 회차는 null", example = "5") val maxApplicants: Int?,
    @field:Schema(description = "확정 및 User 저장 중인 인원. 대상별 정원 회차는 null", example = "88") val confirmed: Long? = 0,
    @field:Schema(description = "대기 일행 인원 합", example = "0") val waiting: Long = 0,
    @field:Schema(description = "Application이 적용한 대기 정원. 미적용이면 null", example = "10") val appliedWaitingCapacity: Int? = null,
    @field:Schema(description = "남은 대기석", example = "10") val waitingRemaining: Int = 0,
    @field:Schema(description = "한 번에 대기 신청할 수 있는 최대 인원. 대기 접수 중지 시 0", example = "5") val maxWaitingApplicants: Int = 0,
    @field:Schema(description = "ADOPTED 또는 신청 이력 후 Expo 값이 적용되지 않은 UNADOPTED", example = "ADOPTED")
    val waitingCapacityStatus: String = "ADOPTED",
    @field:Schema(description = "Expo의 현재 대기 정원. UNADOPTED일 때 채택 요청의 expected 값", example = "10")
    val expoWaitingCapacity: Int? = null,
    @field:Schema(description = "대상별 정원 회차의 공개 좌석. 구분 없는 회차는 null")
    val targetSeats: PreregisterTargetSeats? = null,
)
