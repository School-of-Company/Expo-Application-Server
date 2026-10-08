package team.startup.application.domain.application.presentation.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.hibernate.validator.constraints.CodePointLength
import java.time.Instant
import java.util.UUID

data class PreregisterSessionDefinition(
    @field:Positive val id: Long,
    val expoId: UUID,
    @field:NotBlank @field:CodePointLength(max = 50) val title: String,
    val startedAt: Instant,
    val endedAt: Instant,
    @field:NotBlank val place: String,
    @field:Positive val capacity: Int,
    @field:PositiveOrZero @param:JsonProperty(required = true) val waitingCapacity: Int,
    @param:JsonProperty(required = true) val closed: Boolean,
    @field:Positive val revision: Long,
)

enum class PreregisterChangeOperation { UPDATE, DELETE }

data class PreregisterSessionChangeRequest(
    val operation: PreregisterChangeOperation,
    @param:JsonProperty(required = true) val definitionChanged: Boolean,
    @field:Valid val definition: PreregisterSessionDefinition?,
)

data class PreparePreregisterSessionChangeCommand(
    val expoId: UUID,
    val sessionId: Long,
    val nextRevision: Long,
    val request: PreregisterSessionChangeRequest,
)

// Participant IDs and representative ownership must be resolved by a trusted upstream, not from form answers.
data class ApplyPreregisterSessionCommand(
    val expoId: UUID,
    val sessionId: Long,
    val requestId: String,
    val representativeId: Long,
    val participantIds: List<Long>,
)

data class PreregisterApplicationReceipt(
    val id: Long,
    val participantId: Long,
    val sessionId: Long,
    val status: String,
)

data class CancelPreregisterSessionCommand(
    val expoId: UUID,
    val sessionId: Long,
    val representativeId: Long?,
    val applicationIds: List<Long>,
)

data class PreregisterSessionKey(
    val expoId: UUID,
    val sessionId: Long,
)

internal fun PreregisterSessionDefinition.isValidDefinition(): Boolean =
    id > 0 && revision > 0 && capacity > 0 && waitingCapacity >= 0 &&
        title.isNotBlank() && title.codePointCount(0, title.length) <= 50 && place.isNotBlank() &&
        startedAt.isBefore(endedAt) && startedAt.nano % 1000 == 0 && endedAt.nano % 1000 == 0
