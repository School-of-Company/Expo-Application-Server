package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CreateReportApplicationSnapshotRequest
import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import team.startup.application.domain.application.presentation.dto.ReportApplicationTarget
import team.startup.application.domain.application.presentation.dto.ReportRegistrationSource
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.repository.ReportApplicationSnapshotRepository
import team.startup.application.domain.application.service.CreateReportApplicationSnapshotService
import java.util.UUID

@Service
class CreateReportApplicationSnapshotServiceImpl(
    private val applications: AdminApplicationQueryRepository,
    private val snapshots: ReportApplicationSnapshotRepository,
    private val participants: AdminParticipantGateway,
    transactionManager: PlatformTransactionManager,
) : CreateReportApplicationSnapshotService {
    private val writes =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        request: CreateReportApplicationSnapshotRequest,
    ): ReportApplicationSnapshotPage {
        val participantIds = participants.resolve(expoId, request.filter.q, request.filter.occupation?.name)
        return requireNotNull(
            writes.execute {
                val rows = applications.targets(expoId, request.filter.query, 10001, participantIds = participantIds)
                if (rows.size > 10000) {
                    throw ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Report target limit is 10000 applications; narrow the filter",
                    )
                }
                snapshots.create(
                    expoId,
                    rows.map { ReportApplicationTarget(expoId, it, ReportRegistrationSource(expoId, it.participantId)) },
                    request.limit,
                )
            },
        )
    }
}
