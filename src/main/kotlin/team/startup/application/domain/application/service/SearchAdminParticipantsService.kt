package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminParticipantPage
import team.startup.application.domain.application.presentation.dto.AdminParticipantSearchQuery
import java.util.UUID

interface SearchAdminParticipantsService {
    fun execute(
        expoId: UUID,
        query: AdminParticipantSearchQuery,
    ): AdminParticipantPage
}
