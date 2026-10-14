package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminApplicationPageViews
import team.startup.application.domain.application.presentation.dto.RecordApplicationPageViewCommand
import java.util.UUID

interface RecordApplicationPageViewService {
    fun execute(command: RecordApplicationPageViewCommand)
}

interface GetAdminApplicationPageViewsService {
    fun execute(expoId: UUID): AdminApplicationPageViews
}
