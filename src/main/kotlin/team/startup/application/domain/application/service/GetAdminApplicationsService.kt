package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminApplicationPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import java.util.UUID

interface GetAdminApplicationsService {
    fun execute(
        expoId: UUID,
        query: AdminApplicationQuery,
    ): AdminApplicationPage
}
