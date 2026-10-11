package team.startup.application.domain.application.presentation.dto

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
)

data class ProgramSessionDefinition(
    val expoId: String,
    val programType: ProgramSessionType,
    val id: Long,
    val groupId: UUID,
    val revision: Long,
    val policy: ProgramSessionPolicy?,
    val pendingRevision: Long? = null,
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
