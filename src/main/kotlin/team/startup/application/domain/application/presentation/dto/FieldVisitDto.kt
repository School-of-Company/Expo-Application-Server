package team.startup.application.domain.application.presentation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class NewFieldParticipant(
    @field:NotBlank @field:Size(max = 100) val clientReference: String,
    @field:NotBlank @field:Size(max = 10) val name: String,
    @field:NotBlank val occupation: String,
    @field:Size(max = 100) val school: String? = null,
    @field:NotBlank val region: String,
    val informationJson: String = "{}",
)

data class RegisterFieldVisitRequest(
    @field:Size(max = 5) val participantIds: List<@Positive Long> = emptyList(),
    @field:Valid @field:Size(max = 5) val newParticipants: List<NewFieldParticipant> = emptyList(),
    val personalInformationStatus: Boolean,
)

data class FieldVisitCommand(
    val expoId: UUID,
    val sessionId: String,
    val requestId: String,
    val request: RegisterFieldVisitRequest,
) {
    override fun toString() = "FieldVisitCommand(expoId=$expoId)"
}

data class FieldVisitParticipantResult(
    val participantId: Long,
    val clientReference: String? = null,
    val outcome: String,
    val registration: FieldVisitRegistration? = null,
)

data class FieldVisitReceipt(
    val visitDate: LocalDate,
    val participants: List<FieldVisitParticipantResult>,
)

data class FieldVisitPartyParticipant(
    val participantId: Long,
    val name: String,
    val representative: Boolean,
    val preregistered: Boolean,
    val fieldVisit: FieldVisitRegistration?,
    val legacyFieldDateUnknown: Boolean,
)

data class FieldVisitParty(
    val visitDate: LocalDate,
    val participants: List<FieldVisitPartyParticipant>,
)

data class FieldParticipantResolution(
    val participantId: Long,
    val clientReference: String,
)

data class FieldVisitSourceQuery(
    val visitDate: LocalDate? = null,
    val cursor: UUID? = null,
    @field:Min(1) @field:Max(500) val size: Int = 100,
)

data class FieldVisitSourceCommand(
    val expoId: UUID,
    val query: FieldVisitSourceQuery,
)

data class FieldVisitEvent(
    val registrationId: UUID,
    val generation: Long,
    val eventType: String,
    val occurredAt: Instant,
    val actorParticipantId: Long?,
    val operationId: UUID,
)

data class FieldVisitSourcePage(
    val items: List<FieldVisitRegistration>,
    val events: List<FieldVisitEvent>,
    val nextCursor: UUID?,
)
