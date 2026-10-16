package team.startup.application.domain.application.presentation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.util.UUID

data class StudentGuardianBatchRequest(
    @field:Valid
    @field:Size(min = 2)
    val participants: List<StudentGuardianSubmission>,
    @field:Valid
    @field:Size(min = 1)
    val links: List<StudentGuardianLink>,
)

data class StudentGuardianSubmission(
    @field:Positive val participantId: Long,
    @field:Positive val formVersion: Long? = null,
    val answers: JsonNode? = null,
)

data class StudentGuardianLink(
    @field:Positive val studentId: Long,
    @field:Positive val guardianId: Long,
)

data class ApplyStudentGuardianBatchCommand(
    val key: ProgramSessionKey,
    val sessionId: String,
    val idempotencyKey: String,
    val request: StudentGuardianBatchRequest,
) {
    override fun toString() = "ApplyStudentGuardianBatchCommand(key=$key)"
}

data class StudentGuardianBatchReceipt(
    val batchId: UUID,
    val applications: List<StudentGuardianBatchApplication>,
)

data class StudentGuardianBatchApplication(
    val application: ProgramSessionReceipt,
    val reused: Boolean,
)

data class CancelStudentGuardianApplicationCommand(
    val expoId: UUID,
    val applicationId: UUID,
    val sessionId: String? = null,
    val authorization: String? = null,
) {
    override fun toString() = "CancelStudentGuardianApplicationCommand(expoId=$expoId, applicationId=$applicationId)"
}

enum class StudentGuardianValidationPhase { SUBMISSION, PROMOTION }

data class StudentGuardianEligibilityQuery(
    val definition: ProgramSessionDefinition,
    val participants: Set<Long>,
    val links: List<StudentGuardianLink>,
    val phase: StudentGuardianValidationPhase,
    val reusedGuardianIds: Set<Long> = emptySet(),
    val submissions: List<StudentGuardianSubmission> = emptyList(),
)
