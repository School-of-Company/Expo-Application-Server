package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.service.GetOwnerApplicationsService

@Service
class GetOwnerApplicationsServiceImpl(
    private val gateway: RegistrationGateway,
    private val repository: OwnerApplicationRepository,
) : GetOwnerApplicationsService {
    @Transactional(readOnly = true)
    override fun execute(command: GetOwnerApplicationsCommand): OwnerApplicationsResponse {
        val party = gateway.managedParty(command.expoId, command.sessionId)
        return OwnerApplicationsResponse(party.participants, repository.applications(command.expoId, party))
    }
}
