package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.AdminApplicationPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationReadCommand
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.service.GetAdminApplicationsService

@Service
class GetAdminApplicationsServiceImpl(
    private val repository: AdminApplicationQueryRepository,
    private val participants: AdminParticipantGateway,
    transactionManager: PlatformTransactionManager,
) : GetAdminApplicationsService {
    private val reads =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AdminApplicationReadCommand): AdminApplicationPage {
        val (expoId, query, includeParticipantDetails) = command
        val candidates = participants.resolve(expoId, query.q, query.occupation?.name)
        val (items, counts) = requireNotNull(reads.execute { repository.query(expoId, query, candidates) })
        val details = if (includeParticipantDetails) participants.details(expoId, items.map { it.participantId }.distinct()) else emptyMap()
        return AdminApplicationPage(
            items.map {
                it.copy(participant = details[it.participantId])
            },
            counts.values.sum(),
            counts,
            query.page,
            query.size,
        )
    }
}
