package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import java.util.UUID

@Service
class PurgePreregisterSessionsServiceImpl(
    private val repository: PreregisterSessionRepository,
) : PurgePreregisterSessionsService {
    @Transactional
    override fun execute(expoId: UUID) {
        repository.lock(expoId)
        repository.purge(expoId)
    }
}
