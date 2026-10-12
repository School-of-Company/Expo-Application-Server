package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryQuery
import team.startup.application.domain.application.service.GetAdminExpoHistoryService
import java.util.UUID

@Service
class GetAdminExpoHistoryServiceImpl(
    private val gateway: AdminExpoGateway,
) : GetAdminExpoHistoryService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        authorization: String?,
        query: AdminExpoHistoryQuery,
    ): AdminExpoHistoryPage {
        gateway.requireAccess(expoId, authorization)
        return gateway.history(expoId, requireNotNull(authorization), query)
    }
}
