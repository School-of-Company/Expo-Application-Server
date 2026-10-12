package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSummary
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetAdminPreregisterSummaryService
import java.util.UUID

@Service
class GetAdminPreregisterSummaryServiceImpl(
    private val repository: PreregisterSessionRepository,
) : GetAdminPreregisterSummaryService {
    @Transactional(readOnly = true)
    override fun execute(expoId: UUID): AdminPreregisterSummary {
        repository.readLock(expoId)
        if (repository.expoDeleted(expoId)) throw ResponseStatusException(HttpStatus.CONFLICT, "삭제된 행사입니다.")
        return repository.adminSummary(expoId)
    }
}
