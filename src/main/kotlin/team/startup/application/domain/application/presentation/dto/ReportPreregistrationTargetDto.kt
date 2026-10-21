package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

@Schema(description = "사전등록 접수대장 필터와 같은 의미. 페이지 없음")
data class ReportPreregistrationFilter(
    @field:Positive val sessionId: Long? = null,
    @param:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    @field:Size(min = 1) val status: List<AdminPreregistrationStatus>? = null,
    @field:Size(min = 1, max = 50) val q: String? = null,
) : StrictPreregistrationRequest() {
    @get:AssertTrue(message = "q must not be blank")
    @get:JsonIgnore
    val isQueryValid: Boolean get() = q == null || q.isNotBlank()

    @get:JsonIgnore
    val query: AdminPreregistrationQuery get() = AdminPreregistrationQuery(sessionId, status, q)
}

@Schema(description = "접수대장 key 선택. 최대 10000건, 중복 금지")
data class SelectReportPreregistrationTargetsRequest(
    @field:Size(min = 1, max = 10000)
    val keys: List<
        @Size(min = 1, max = 160)
        String,
    >,
) : StrictPreregistrationRequest() {
    @get:AssertTrue(message = "keys must be unique")
    @get:JsonIgnore
    val isKeysUnique: Boolean get() = keys.distinct().size == keys.size
}

@Schema(description = "사전등록 전체 보고 대상 스냅샷 생성. 최대 10000행, 페이지 1–500", example = """{"filter":{},"limit":100}""")
data class CreateReportPreregistrationSnapshotRequest(
    @field:Valid val filter: ReportPreregistrationFilter = ReportPreregistrationFilter(),
    @field:Min(1) @field:Max(500) val limit: Int = 100,
) : StrictPreregistrationRequest()

enum class ReportPreregistrationRowType { APPLICATION, RESERVATION }

enum class ReportPreregistrationReservationKind { PARTY, COMPANION_ADDITION, SESSION_ADDITION }

enum class ReportSeatCategory { TEACHER, OTHER, UNRECORDED }

@Schema(description = "회차의 현재 저장 정의. 제출 당시 정의가 아니며, 저장 정의에 없는 값은 null")
data class ReportPreregistrationSession(
    val sessionId: Long,
    val title: String?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val place: String?,
    val capacity: Int?,
    @field:Schema(description = "교사·예비교사 별도 정원. nonTeacherCapacity와 함께 null이면 구분 없는 회차")
    val teacherCapacity: Int?,
    val nonTeacherCapacity: Int?,
    val deleted: Boolean,
)

@Schema(description = "예약 일행의 신청 당시 구분별 인원. unrecorded = applicants - teacher - other")
data class ReportSeatCounts(
    val teacher: Int,
    val other: Int,
    val unrecorded: Int,
)

@Schema(description = "신청 원장 전이 1건. 과거 미기록 시각은 null, 실행자는 기록된 값만")
data class ReportPreregistrationTransition(
    val status: String,
    val createdAt: Instant?,
    val actorType: ApplicationActorType,
    val actorParticipantId: Long?,
    val actorId: Long?,
)

@Schema(
    description =
        "사전등록 보고 대상 1행. APPLICATION은 User 참가자×회차, RESERVATION은 User 미저장 예약 일행×회차. " +
            "reservationId는 participantId가 아니며 예약 행은 QR 발급 대상이 아니다.",
)
data class ReportPreregistrationTarget(
    val expoId: UUID,
    val key: String,
    val rowType: ReportPreregistrationRowType,
    val status: AdminPreregistrationStatus,
    val applicationId: Long?,
    val reservationId: String?,
    @field:Schema(description = "User 참가자 ID. 예약 행은 항상 null")
    val participantId: Long?,
    @field:Schema(description = "원장 대표자 참가자 ID. 예약 행은 동행자 추가 예약의 기존 대표자만, 그 외 null")
    val representativeId: Long?,
    @field:Schema(description = "APPLICATION 행에서 participantId == representativeId. 예약 행은 null(일행 대표는 registrationSnapshot)")
    val representative: Boolean?,
    val reservationKind: ReportPreregistrationReservationKind?,
    val applicants: Int,
    @field:Schema(description = "APPLICATION 행의 확정 시 좌석 구분. 예약 행은 null")
    val seatCategory: ReportSeatCategory?,
    @field:Schema(description = "RESERVATION 행의 구분별 인원. APPLICATION 행은 null")
    val seatCounts: ReportSeatCounts?,
    val session: ReportPreregistrationSession,
    @field:Schema(description = "APPLICATION 원장 행 생성(확정) 시각. 예약은 접수 시각이 저장되지 않아 null")
    val createdAt: Instant?,
    val cancelledAt: Instant?,
    val cancellationActorType: ApplicationActorType?,
    val cancellationActorUser: ApplicationUserActor?,
    val cancellationActorId: Long?,
    @field:Schema(description = "APPLICATION 행의 확정·취소 전이(오래된 순). 예약 행은 빈 배열")
    val history: List<ReportPreregistrationTransition>,
    @field:Schema(description = "대기열을 거쳤으면 true. APPLICATION 행에서 연결 예약이 없는 과거 행은 null")
    val promotedFromWaiting: Boolean?,
    @field:Schema(
        description =
            "신청 당시 일행 제출 스냅샷(name, phoneNumber, occupation, informationJson, questions). " +
                "동행자 행도 대표 제출본이며, 연결 예약이 없는 과거 행은 null",
    )
    val registrationSnapshot: JsonNode?,
    @field:Schema(description = "조회 시점 User 현재 프로필. 예약 행은 null")
    val participant: AdminParticipantDetail?,
)

@Schema(description = "선택한 사전등록 보고 대상")
data class ReportPreregistrationSelection(
    val items: List<ReportPreregistrationTarget>,
    val totalElements: Int,
)

@Schema(description = "15분간 고정된 사전등록 보고 대상 페이지와 다음 커서")
data class ReportPreregistrationSnapshotPage(
    val snapshotId: UUID,
    val snapshotAt: Instant,
    val expiresAt: Instant,
    val totalElements: Int,
    val items: List<ReportPreregistrationTarget>,
    val nextCursor: String?,
)
