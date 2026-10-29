package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@Schema(description = "QR 문자에 싣는 신청 내역 조회 요청(Notification #136). 일행 한 팀의 참가자 ID")
data class ParticipantNoticeItemsRequest(
    @field:NotEmpty @field:Size(max = 5)
    @field:Schema(description = "참가자 ID. 1~5개, 중복·0 이하는 400", example = "[42, 43]")
    val participantIds: List<Long>,
)

@Schema(description = "확정된 사전등록 회차")
data class ParticipantNoticeSession(
    val title: String,
    @field:Schema(description = "한국 시간 오프셋을 포함한 ISO 일시", example = "2026-10-31T10:00:00+09:00")
    val startedAt: String,
    val endedAt: String,
    val place: String?,
)

@Schema(description = "확정된 프로그램 신청. 시작·종료 시각을 읽을 수 없으면 둘 다 null")
data class ParticipantNoticeProgram(
    val programType: ProgramSessionType,
    val title: String,
    val startedAt: String?,
    val endedAt: String?,
    val location: String?,
)

@Schema(description = "참가자별 확정 회차와 프로그램. 확정된 것이 없으면 빈 배열")
data class ParticipantNoticeItems(
    val participantId: Long,
    val sessions: List<ParticipantNoticeSession>,
    val programs: List<ParticipantNoticeProgram>,
)
