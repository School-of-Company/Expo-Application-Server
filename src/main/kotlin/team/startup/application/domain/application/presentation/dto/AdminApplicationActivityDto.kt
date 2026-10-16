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
    val participantId: Long,
    val programType: ProgramSessionType,
    val programId: Long,
    val action: AdminApplicationAction,
    val status: AdminApplicationStatus,
    val actorId: Long?,
    val createdAt: Instant,
    val actorType: ApplicationActorType,
    val previousStatus: AdminApplicationStatus?,
    @field:Schema(description = "false는 과거 미기록, true이면서 previousStatus=null은 최초 신청")
    val previousStatusRecorded: Boolean,
    val receiptNumber: Long,
    @field:Schema(description = "제출 당시 프로그램 정의. 삭제된 회차도 보존하며 누락 필드는 추정하지 않음")
    val definition: JsonNode,
    @field:Schema(description = "현재 User 참가자 정보. 처리 당시 정보가 아님")
    val participant: AdminParticipantDetail? = null,
)

data class AdminApplicationActivityPage(
    val items: List<AdminApplicationActivity>,
    val totalElements: Long,
    val page: Int,
    val size: Int,
)
