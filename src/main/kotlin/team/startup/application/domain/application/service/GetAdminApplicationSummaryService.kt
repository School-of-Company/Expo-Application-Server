package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationSummary
import java.util.UUID

interface GetAdminApplicationSummaryService {
    fun execute(
        expoId: UUID,
        query: AdminApplicationQuery,
    ): AdminApplicationSummary
}
