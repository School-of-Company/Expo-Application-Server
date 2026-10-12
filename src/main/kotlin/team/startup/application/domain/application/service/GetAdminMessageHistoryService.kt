package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryQuery
import java.util.UUID

interface GetAdminMessageHistoryService {
    fun execute(
        expoId: UUID,
        authorization: String?,
        query: AdminMessageHistoryQuery,
    ): AdminMessageHistoryPage
}
