package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.FieldVisitParty
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand

interface GetFieldVisitPartyService {
    fun execute(command: GetOwnerApplicationsCommand): FieldVisitParty
}
