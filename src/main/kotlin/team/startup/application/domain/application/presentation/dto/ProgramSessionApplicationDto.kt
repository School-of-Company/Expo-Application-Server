package team.startup.application.domain.application.presentation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class ProgramSessionType { STANDARD, TRAINING }

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

// This command is service-only until the public route and cookie/CSRF contract are agreed.
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

data class ProgramSessionReceipt(
    val applicationId: UUID,
    val programId: Long,
    val participantId: Long,
    val traineeId: Long?,
    val status: String = "CONFIRMED",
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

data class ProgramMutation(
    @field:Valid
    val previous: ProgramSessionDefinition?,
    @field:Valid
    val definition: ProgramSessionDefinition?,
)

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
