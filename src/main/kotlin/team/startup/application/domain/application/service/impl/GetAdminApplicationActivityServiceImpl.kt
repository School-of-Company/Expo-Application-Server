package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityQuery
import team.startup.application.domain.application.repository.AdminApplicationActivityRepository
import team.startup.application.domain.application.service.GetAdminApplicationActivityService
import java.util.UUID

@Service
class GetAdminApplicationActivityServiceImpl(
    private val repository: AdminApplicationActivityRepository,
    private val participants: AdminParticipantGateway,
    transactionManager: PlatformTransactionManager,
) : GetAdminApplicationActivityService {
    private val reads =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        query: AdminApplicationActivityQuery,
    ): AdminApplicationActivityPage {
        val candidates = participants.resolve(expoId, query.q, null)
        val page = requireNotNull(reads.execute { repository.find(expoId, query, candidates) })
        val details = participants.details(expoId, page.items.map { it.participantId }.distinct())
        val names = participants.names(expoId, page.items.mapNotNull { it.actorUser?.participantId })
        return page.copy(
            items =
                page.items.map { item ->
                    item.copy(
                        participant = details.getValue(item.participantId),
                        actorUser = item.actorUser?.let { it.copy(name = names?.get(it.participantId)) },
                    )
                },
        )
    }
}
