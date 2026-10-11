package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionPage
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionQuery
import team.startup.application.domain.application.service.GetAdminCurrentSessionsService
import java.util.UUID

@Service
class GetAdminCurrentSessionsServiceImpl(
    private val expo: AdminExpoGateway,
) : GetAdminCurrentSessionsService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        query: AdminCurrentSessionQuery,
    ): AdminCurrentSessionPage = expo.sessions(expoId, query)
}
