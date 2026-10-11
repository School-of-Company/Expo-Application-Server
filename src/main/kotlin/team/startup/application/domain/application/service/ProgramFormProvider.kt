package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import tools.jackson.databind.JsonNode
import java.util.UUID

interface ProgramFormProvider {
    // The adapter must validate question IDs, required answers, types and option IDs against this exact version.
    fun loadAndValidate(
        expoId: String,
        formId: UUID,
        version: Long,
        answers: JsonNode,
    ): ProgramFormSnapshot
}
