package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityQuery
import team.startup.application.domain.application.repository.AdminPreregistrationActivityRepository
import team.startup.application.domain.application.service.GetAdminPreregistrationActivityService
import java.util.UUID

@Service
class GetAdminPreregistrationActivityServiceImpl(
    private val repository: AdminPreregistrationActivityRepository,
    private val participants: AdminParticipantGateway,
    transactionManager: PlatformTransactionManager,
) : GetAdminPreregistrationActivityService {
    private val reads =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        query: AdminPreregistrationActivityQuery,
    ): AdminPreregistrationActivityPage {
        val candidates = participants.resolve(expoId, query.q, null)
        val page = requireNotNull(reads.execute { repository.find(expoId, query, candidates) })
        val details = participants.details(expoId, page.items.mapNotNull { it.participantId }.distinct())
        val names = participants.names(expoId, page.items.mapNotNull { it.actorUser?.participantId })
        return page.copy(
            items =
                page.items.map { item ->
                    item.copy(
                        participant = item.participantId?.let { details.getValue(it) },
                        actorUser =
                            item.actorUser?.let { actor ->
                                actor.copy(
                                    name =
                                        actor.participantId?.let { names?.get(it) } ?: actor.name,
                                )
                            },
                    )
                },
        )
    }
}
