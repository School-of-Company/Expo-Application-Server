package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class AdminApplicationAction { APPLIED, CANCELLED, PROMOTED }

enum class ApplicationActorType { USER, ADMIN, SYSTEM, UNKNOWN }

enum class ApplicationActorRole { REPRESENTATIVE }

enum class ApplicationActorNameSource { CURRENT_USER, REGISTRATION_SNAPSHOT }

@Schema(
    description =
        "실제 취소를 실행한 사용자. 인증 세션으로 확인한 대표자이며 취소 대상과 다를 수 있음. " +
            "participantId(User 참가자) 또는 reservationRequestId(User 미저장 대기 예약) 중 하나만 있음",
)
data class ApplicationUserActor(
    @field:Schema(description = "User 대표 참가자 ID. 대기 예약 실행자는 null")
    val participantId: Long?,
    @field:Schema(
        description = "같은 행사의 원본 대기 예약 requestId. 예약 단위 식별이며 여러 예약에 걸친 동일인 ID가 아님",
    )
    val reservationRequestId: String?,
    val role: ApplicationActorRole,
    @field:Schema(
        description = "CURRENT_USER는 조회 시점 User 이름(조회 실패·누락 시 null), REGISTRATION_SNAPSHOT은 대기 신청 당시 이름",
    )
    val name: String?,
    val nameSource: ApplicationActorNameSource,
)

data class AdminApplicationActivityQuery(
    val action: AdminApplicationAction? = null,
    val applicationId: UUID? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(100) val size: Int = 20,
    @field:Size(min = 1, max = 50) val q: String? = null,
    val programType: ProgramSessionType? = null,
    @field:Positive val programId: Long? = null,
    @field:Schema(description = "이력에 기록된 처리 결과 상태. 현재 신청 상태가 아님")
    val status: AdminApplicationStatus? = null,
) {
    @get:AssertTrue(message = "q must not be blank")
    val isParticipantQueryValid: Boolean get() = q == null || q.isNotBlank()

    @get:AssertTrue(message = "programId requires programType")
    val isProgramTypeValid: Boolean get() = programId == null || programType != null
}

data class AdminApplicationActivity(
    val id: Long,
    val applicationId: UUID,
    @field:Schema(description = "취소·신청 대상 참가자 ID. 실제 실행자가 아님")
    val participantId: Long,
    val programType: ProgramSessionType,
    val programId: Long,
    val action: AdminApplicationAction,
    val status: AdminApplicationStatus,
    @field:Schema(description = "ADMIN 처리의 관리자 ID. 그 외는 null")
    val actorId: Long?,
    val createdAt: Instant,
    @field:Schema(
        description =
            "UNKNOWN은 주체 미기록 과거 이력 또는 인증 주체 없는 내부 호출. " +
                "USER 신청 이력과 #143 이전 USER 취소는 실행 대표자 미기록(actorUser=null)",
    )
    val actorType: ApplicationActorType,
    val previousStatus: AdminApplicationStatus?,
    @field:Schema(description = "false는 과거 미기록, true이면서 previousStatus=null은 최초 신청")
    val previousStatusRecorded: Boolean,
    val receiptNumber: Long,
    @field:Schema(description = "제출 당시 프로그램 정의. 삭제된 회차도 보존하며 누락 필드는 추정하지 않음")
    val definition: JsonNode,
    @field:Schema(description = "현재 User 참가자 정보. 처리 당시 정보가 아님")
    val participant: AdminParticipantDetail? = null,
    @field:Schema(description = "USER 취소의 실제 실행 대표자. 신청 이력·과거 미기록·USER 외 주체는 null")
    val actorUser: ApplicationUserActor? = null,
)

data class AdminApplicationActivityPage(
    val items: List<AdminApplicationActivity>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)
