package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityQuery
import java.util.UUID

interface GetAdminPreregistrationActivityService {
    fun execute(
        expoId: UUID,
        query: AdminPreregistrationActivityQuery,
    ): AdminPreregistrationActivityPage
}
