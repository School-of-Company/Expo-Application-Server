package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionPage
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionQuery
import java.util.UUID

interface GetAdminCurrentSessionsService {
    fun execute(
        expoId: UUID,
        query: AdminCurrentSessionQuery,
    ): AdminCurrentSessionPage
}
