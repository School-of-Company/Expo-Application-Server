package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryQuery
import java.util.UUID

interface GetAdminExpoHistoryService {
    fun execute(
        expoId: UUID,
        authorization: String?,
        query: AdminExpoHistoryQuery,
    ): AdminExpoHistoryPage
}
