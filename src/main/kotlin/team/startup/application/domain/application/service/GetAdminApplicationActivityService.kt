package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityQuery
import java.util.UUID

interface GetAdminApplicationActivityService {
    fun execute(
        expoId: UUID,
        query: AdminApplicationActivityQuery,
    ): AdminApplicationActivityPage
}
