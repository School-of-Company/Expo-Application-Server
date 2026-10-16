package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AdminApplicationStatus { CONFIRMED, WAITING, CANCELLED }

enum class AdminApplicationSort {
    CREATED_ASC,
    CREATED_DESC,
    RECEIPT_ASC,
    RECEIPT_DESC,
    PROGRAM_ASC,
    PROGRAM_DESC,
    SESSION_ASC,
    SESSION_DESC,
}

enum class AdminOccupation {
    KINDERGARTEN_STUDENT,
    ELEMENTARY_STUDENT,
    MIDDLE_SCHOOL_STUDENT,
    HIGH_SCHOOL_STUDENT,
    SCHOOL_STAFF,
    PARENT,
    GENERAL,
    TEACHER,
    PRE_SERVICE_TEACHER,
}

@Schema(description = "관리 원장 검색·페이지 조건")
data class AdminApplicationQuery(
    val programType: ProgramSessionType? = null,
    @field:Positive val programId: Long? = null,
    val groupId: UUID? = null,
    val applicationId: UUID? = null,
    @field:Schema(description = "쉼표로 여러 상태 지정 가능. 예: CONFIRMED,WAITING")
    val status: List<AdminApplicationStatus>? = null,
    @field:Size(max = 200) val search: String? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    val sort: AdminApplicationSort = AdminApplicationSort.CREATED_DESC,
    @field:Size(min = 1, max = 50) val q: String? = null,
    val occupation: AdminOccupation? = null,
    @field:Schema(description = "제출 당시 회차 시작 일시(KST 로컬)의 날짜", example = "2026-10-12")
    val eventDate: LocalDate? = null,
) {
    @get:AssertTrue(message = "q must not be blank")
    val isParticipantQueryValid: Boolean get() = q == null || q.isNotBlank()

    @get:AssertTrue(message = "from must precede until")
    val isDateRangeValid: Boolean get() = from == null || until == null || from < until

    @get:AssertTrue(message = "programId requires programType")
    val isProgramTypeValid: Boolean get() = programId == null || programType != null
}

@Schema(description = "접수 시점의 정의·폼·답변과 상태 이력")
data class AdminApplicationRow(
    val applicationId: UUID,
    @field:Schema(description = "박람회별 1부터 증가하는 접수번호. 접수 커밋 순서이며 재사용하지 않음")
    val receiptNumber: Long,
    val participantId: Long,
    val traineeId: Long?,
    val programType: ProgramSessionType,
    val programId: Long,
    val groupId: UUID,
    val status: AdminApplicationStatus,
    val createdAt: Instant,
    val formId: UUID,
    val formVersion: Long,
    @field:Schema(description = "신청 당시 정의 스냅샷. place는 당시 장소이며 현재 장소는 current-sessions에서 조회")
    val definition: JsonNode,
    val questions: JsonNode,
    val answers: JsonNode,
    val history: List<AdminApplicationHistory>,
    val participant: AdminParticipantDetail? = null,
)

data class AdminApplicationReadCommand(
    val expoId: UUID,
    val query: AdminApplicationQuery,
    val includeParticipantDetails: Boolean,
)

data class AdminParticipantDetail(
    val participantId: Long,
    val name: String,
    val phoneNumber: String?,
    val notificationPhoneNumber: String?,
    val occupation: AdminOccupation?,
    val representativeId: Long?,
    val linkedTraineeId: Long?,
    val applicationType: String,
    val information: JsonNode,
)

data class AdminExpoAccess(
    val expoId: UUID,
    val adminId: Long,
    val permission: String,
    val policyVersion: String,
    val scope: String,
)

data class AuthorizeAdminApplicationCommand(
    val expoId: UUID,
    val authorization: String?,
)

data class AdminCurrentSessionQuery(
    val programType: ProgramSessionType? = null,
    @field:Positive val programId: Long? = null,
    val groupId: UUID? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    @field:Size(min = 1, max = 100) val definitionVersion: String? = null,
) {
    @get:AssertTrue(message = "programId requires programType; subsequent pages require definitionVersion")
    val isSelectionValid: Boolean get() = (programId == null || programType != null) && (page == 0 || !definitionVersion.isNullOrBlank())
}

data class AdminCurrentSessionPage(
    val expoId: UUID,
    val items: List<JsonNode>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val definitionVersion: String,
)

@Schema(description = "접수 상태 변경 이력")
data class AdminApplicationHistory(
    val id: Long,
    val status: AdminApplicationStatus,
    val createdAt: Instant,
)

@Schema(description = "관리 원장 페이지와 동일 필터 집계")
data class AdminApplicationPage(
    val items: List<AdminApplicationRow>,
    val totalElements: Long,
    val statusCounts: Map<AdminApplicationStatus, Long>,
    val page: Int,
    val size: Int,
)

@Schema(description = "접수와 출석 집계")
data class AdminApplicationSummary(
    val totalElements: Long,
    val uniqueParticipants: Long,
    val statusCounts: Map<AdminApplicationStatus, Long>,
    val totalSessions: Long,
    val sessions: List<AdminApplicationSessionSummary>,
    val page: Int,
    val size: Int,
)

@Schema(description = "프로그램 회차별 접수 집계")
data class AdminApplicationSessionSummary(
    val programType: ProgramSessionType,
    val programId: Long,
    val groupId: UUID,
    val totalElements: Long,
    val uniqueParticipants: Long,
    val statusCounts: Map<AdminApplicationStatus, Long>,
    val attendance: AdminProgramAttendanceSummary? = null,
)

@Schema(description = "Attendance 원천의 실제 출석 인원과 HH:mm 입실 범위")
data class AdminProgramAttendanceSummary(
    val programType: ProgramSessionType,
    val programId: Long,
    val attendeeCount: Long,
    val firstEntryTime: String?,
    val lastEntryTime: String?,
)

@Schema(description = "관리자 참가자 검색 조건. q는 User 이름 또는 번호 부분 일치")
data class AdminParticipantSearchQuery(
    @field:Size(min = 1, max = 50) val q: String? = null,
    @field:Min(0) val cursor: Long = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
) {
    @get:AssertTrue(message = "q is required")
    val isQueryValid: Boolean get() = !q.isNullOrBlank()
}

@Schema(description = "User 참가자 id 오름차순 커서 페이지")
data class AdminParticipantPage(
    val items: List<AdminParticipantDetail>,
    val nextCursor: Long?,
)
