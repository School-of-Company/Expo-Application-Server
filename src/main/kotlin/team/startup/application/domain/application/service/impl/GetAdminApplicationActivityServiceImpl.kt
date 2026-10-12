package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityQuery
import team.startup.application.domain.application.repository.AdminApplicationActivityRepository
import team.startup.application.domain.application.service.GetAdminApplicationActivityService
import java.util.UUID

@Service
class GetAdminApplicationActivityServiceImpl(
    private val repository: AdminApplicationActivityRepository,
) : GetAdminApplicationActivityService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: UUID,
        query: AdminApplicationActivityQuery,
    ): AdminApplicationActivityPage = repository.find(expoId, query)
}
