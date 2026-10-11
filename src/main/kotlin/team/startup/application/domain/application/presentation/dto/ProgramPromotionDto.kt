package team.startup.application.domain.application.presentation.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID

data class ProgramPromotionEvent(
    @field:Min(1) @field:Max(1)
    val version: Int,
    val eventId: UUID,
    val applicationId: UUID,
    val expoId: UUID,
    @field:Min(1) @field:Max(9007199254740991)
    val participantId: Long,
    val programType: ProgramSessionType,
    @field:Min(1) @field:Max(9007199254740991)
    val programId: Long,
)

data class ProgramPromotionCheck(
    val eligible: Boolean,
    val reason: String? = null,
    val eventId: UUID? = null,
    val applicationId: UUID? = null,
    val expoId: UUID? = null,
    val participantId: Long? = null,
    val programType: ProgramSessionType? = null,
    val programId: Long? = null,
    val status: String? = null,
    val representativePhoneNumber: String? = null,
    val participantName: String? = null,
    val programName: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val location: String? = null,
    val applicationLookupUrl: String? = null,
) {
    override fun toString() = "ProgramPromotionCheck(eligible=$eligible, reason=$reason, eventId=$eventId)"
}
