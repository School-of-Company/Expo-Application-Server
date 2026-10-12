package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminPreregisterSummary
import java.util.UUID

interface GetAdminPreregisterSummaryService {
    fun execute(expoId: UUID): AdminPreregisterSummary
}
