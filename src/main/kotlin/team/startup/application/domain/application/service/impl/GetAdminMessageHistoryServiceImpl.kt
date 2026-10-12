package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryQuery
import team.startup.application.domain.application.service.GetAdminMessageHistoryService
import java.util.UUID

@Service
class GetAdminMessageHistoryServiceImpl(
    private val gateway: AdminGroupMessageGateway,
) : GetAdminMessageHistoryService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        authorization: String?,
        query: AdminMessageHistoryQuery,
    ): AdminMessageHistoryPage {
        gateway.authorize(expoId, authorization)
        return gateway.history(expoId, query)
    }
}
