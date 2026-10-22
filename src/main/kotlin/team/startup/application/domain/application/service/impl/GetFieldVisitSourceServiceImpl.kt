package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.FieldVisitSourceCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitSourcePage
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.service.GetFieldVisitSourceService

@Service
class GetFieldVisitSourceServiceImpl(
    private val visits: FieldVisitRegistrationRepository,
) : GetFieldVisitSourceService {
    @Transactional(readOnly = true)
    override fun execute(command: FieldVisitSourceCommand): FieldVisitSourcePage = visits.source(command.expoId, command.query)
}
