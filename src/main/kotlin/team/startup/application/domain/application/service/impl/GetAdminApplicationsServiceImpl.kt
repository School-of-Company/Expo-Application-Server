package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminApplicationPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.service.GetAdminApplicationsService
import java.util.UUID

@Service
class GetAdminApplicationsServiceImpl(
    private val repository: AdminApplicationQueryRepository,
) : GetAdminApplicationsService {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun execute(
        expoId: UUID,
        query: AdminApplicationQuery,
    ): AdminApplicationPage {
        val (items, counts) = repository.query(expoId, query)
        return AdminApplicationPage(items, counts.values.sum(), counts, query.page, query.size)
    }
}
