package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationSummary
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.service.GetAdminApplicationSummaryService
import java.util.UUID

@Service
class GetAdminApplicationSummaryServiceImpl(
    private val repository: AdminApplicationQueryRepository,
) : GetAdminApplicationSummaryService {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun execute(
        expoId: UUID,
        query: AdminApplicationQuery,
    ): AdminApplicationSummary = repository.summary(expoId, query)
}
