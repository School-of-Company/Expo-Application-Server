package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationSummary
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.service.GetAdminApplicationSummaryService
import java.util.UUID

@Service
class GetAdminApplicationSummaryServiceImpl(
    private val repository: AdminApplicationQueryRepository,
    private val attendance: AdminAttendanceGateway,
    private val participants: AdminParticipantGateway,
    transactionManager: PlatformTransactionManager,
) : GetAdminApplicationSummaryService {
    private val reads =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        query: AdminApplicationQuery,
    ): AdminApplicationSummary {
        val candidates = participants.resolve(expoId, query.q, query.occupation?.name)
        val summary = requireNotNull(reads.execute { repository.summary(expoId, query, candidates) })
        val counts = attendance.summarize(summary.sessions)
        return summary.copy(sessions = summary.sessions.map { it.copy(attendance = counts.getValue(it.programType to it.programId)) })
    }
}
