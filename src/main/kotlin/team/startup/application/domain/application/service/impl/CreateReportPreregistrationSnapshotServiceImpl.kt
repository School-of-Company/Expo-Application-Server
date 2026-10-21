package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.CreateReportPreregistrationSnapshotRequest
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSnapshotPage
import team.startup.application.domain.application.repository.ReportApplicationSnapshotRepository
import team.startup.application.domain.application.service.CreateReportPreregistrationSnapshotService
import java.util.UUID

@Service
class CreateReportPreregistrationSnapshotServiceImpl(
    private val targets: ReportPreregistrationTargets,
    private val snapshots: ReportApplicationSnapshotRepository,
    transactionManager: PlatformTransactionManager,
) : CreateReportPreregistrationSnapshotService {
    private val writes = TransactionTemplate(transactionManager)

    // User 조회는 트랜잭션 밖에서 끝내고, 모든 행을 읽은 뒤에만 스냅샷을 저장한다. 중간 실패는 부분 스냅샷을 남기지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        request: CreateReportPreregistrationSnapshotRequest,
    ): ReportPreregistrationSnapshotPage {
        val items = targets.read(expoId, request.filter.query)
        return requireNotNull(writes.execute { snapshots.createPreregistration(expoId, items, request.limit) })
    }
}
