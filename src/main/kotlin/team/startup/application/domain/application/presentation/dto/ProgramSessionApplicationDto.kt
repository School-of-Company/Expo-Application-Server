package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class ProgramSessionType { STANDARD, TRAINING }

@Schema(description = "프로그램 신청·취소 정책과 폼 참조")
data class ProgramSessionPolicy(
    val formId: UUID,
    val allowedOccupations: Set<String>,
    val capacity: Int,
    val waitingCapacity: Int,
    val applicationStartedAt: Instant,
    val applicationEndedAt: Instant,
    val closed: Boolean,
    val cancelEndedAt: Instant,
    val promotionEndedAt: Instant,
    val groupId: UUID? = null,
)

@Schema(description = "프로그램 회차 전체 정의")
data class ProgramSessionDefinition(
    val expoId: String,
    val programType: ProgramSessionType,
    val id: Long,
    val groupId: UUID,
    val revision: Long,
    @field:Valid
    val policy: ProgramSessionPolicy?,
    val pendingRevision: Long? = null,
    val title: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val category: String? = null,
)

@Schema(description = "프로그램 회차 신청 요청")
data class ApplyProgramSessionRequest(
    @field:Positive
    @field:Schema(description = "신청 참가자 ID", example = "123")
    val participantId: Long,
    @field:Positive
    @field:Schema(description = "제출에 사용한 프로그램 폼 버전", example = "1")
    val formVersion: Long,
    @field:Schema(description = "프로그램 신청 답변")
    val answers: JsonNode,
)

data class ApplyProgramSessionCommand(
    val expoId: String,
    val programType: ProgramSessionType,
    val programId: Long,
    val participantId: Long,
    val formVersion: Long,
    val answers: JsonNode,
    val sessionId: String,
    val idempotencyKey: String,
) {
    override fun toString() = "ApplyProgramSessionCommand(expoId=$expoId, programId=$programId, participantId=$participantId)"
}

@Schema(description = "프로그램 회차 신청 접수 결과")
data class ProgramSessionReceipt(
    @field:Schema(description = "신청 ID", example = "0199c000-0000-7000-8000-000000000001") val applicationId: UUID,
    @field:Schema(description = "프로그램 ID", example = "1") val programId: Long,
    @field:Schema(description = "참가자 ID", example = "123") val participantId: Long,
    @field:Schema(description = "연수자 ID. 일반 신청에서는 null", example = "456") val traineeId: Long?,
    @field:Schema(description = "접수 상태", example = "CONFIRMED") val status: String = "CONFIRMED",
)

data class ProgramFormSnapshot(
    val expoId: String,
    val id: UUID,
    val purpose: String,
    val version: Long,
    val startedAt: Instant,
    val endedAt: Instant,
    val questions: JsonNode,
)

@Schema(description = "프로그램 변경 전후 정의. 삭제 시 definition은 null")
data class ProgramMutation(
    @field:Valid
    val previous: ProgramSessionDefinition?,
    @field:Valid
    val definition: ProgramSessionDefinition?,
)

@Schema(description = "원자적으로 승인할 프로그램 변경 목록. 1–1000건")
data class ProgramChangePayload(
    @field:Valid
    @field:Size(min = 1, max = 1000)
    val mutations: List<ProgramMutation>,
)

data class PrepareProgramChangeCommand(
    val expoId: UUID,
    val changeId: UUID,
    val payload: ProgramChangePayload,
)

data class ProgramSessionKey(
    val expoId: UUID,
    val programType: ProgramSessionType,
    val programId: Long,
)

data class CancelProgramSessionCommand(
    val applicationId: UUID,
)
