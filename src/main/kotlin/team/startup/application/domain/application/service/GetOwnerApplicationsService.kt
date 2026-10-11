package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse

interface GetOwnerApplicationsService {
    fun execute(command: GetOwnerApplicationsCommand): OwnerApplicationsResponse
}
