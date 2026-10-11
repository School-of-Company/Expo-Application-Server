package team.startup.application.domain.application.presentation.dto

import tools.jackson.databind.JsonNode
import java.util.UUID

// Service-only commands: no public route exists until participant cookie/CSRF and cancellation contracts are ready.
data class GetOwnerApplicationsCommand(
    val expoId: UUID,
    val sessionId: String,
) {
    override fun toString() = "GetOwnerApplicationsCommand(expoId=$expoId)"
}

enum class OwnerApplicationType { PREREGISTER, STANDARD, TRAINING }

data class CancelOwnerApplicationCommand(
    val expoId: UUID,
    val sessionId: String,
    val type: OwnerApplicationType,
    val applicationId: String,
) {
    override fun toString() = "CancelOwnerApplicationCommand(expoId=$expoId, type=$type, applicationId=$applicationId)"
}

data class ManagedOwnerParticipant(
    val participantId: Long,
    val name: String,
    val occupation: String?,
    val representative: Boolean,
)

data class OwnerApplicationParty(
    val participants: List<ManagedOwnerParticipant>,
) {
    val representativeId: Long? get() = participants.singleOrNull { it.representative }?.participantId
}

data class OwnerApplicationHistory(
    val status: String,
)

data class OwnerApplicationItem(
    val applicationId: String,
    val type: OwnerApplicationType,
    val participantId: Long,
    val sessionOrProgramId: Long,
    val status: String,
    val applied: Boolean,
    val questions: JsonNode?,
    val answers: JsonNode?,
    val history: List<OwnerApplicationHistory>,
    val cancellable: Boolean = false,
    val cancellationBlockedReason: String = "EXTERNAL_CONTRACT_REQUIRED",
)

data class OwnerApplicationsResponse(
    val participants: List<ManagedOwnerParticipant>,
    val applications: List<OwnerApplicationItem>,
)
