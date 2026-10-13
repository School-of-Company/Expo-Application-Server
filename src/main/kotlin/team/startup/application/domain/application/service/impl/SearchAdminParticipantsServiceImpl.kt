package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminParticipantPage
import team.startup.application.domain.application.presentation.dto.AdminParticipantSearchQuery
import team.startup.application.domain.application.service.SearchAdminParticipantsService
import java.util.UUID

@Service
class SearchAdminParticipantsServiceImpl(
    private val participants: AdminParticipantGateway,
) : SearchAdminParticipantsService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        query: AdminParticipantSearchQuery,
    ): AdminParticipantPage = participants.search(expoId, query)
}
