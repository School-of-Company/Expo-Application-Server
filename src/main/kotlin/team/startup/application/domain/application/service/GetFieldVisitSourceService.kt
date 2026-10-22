package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.FieldVisitSourceCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitSourcePage

interface GetFieldVisitSourceService {
    fun execute(command: FieldVisitSourceCommand): FieldVisitSourcePage
}
