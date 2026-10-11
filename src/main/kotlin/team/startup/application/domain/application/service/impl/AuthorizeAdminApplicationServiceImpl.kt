package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminExpoAccess
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService

@Service
class AuthorizeAdminApplicationServiceImpl(
    private val expo: AdminExpoGateway,
) : AuthorizeAdminApplicationService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AuthorizeAdminApplicationCommand): AdminExpoAccess =
        expo.requireAccess(command.expoId, command.authorization)
}
