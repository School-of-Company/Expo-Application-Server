package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSnapshotPage
import team.startup.application.domain.application.repository.ReportApplicationSnapshotRepository
import team.startup.application.domain.application.service.GetReportPreregistrationSnapshotService
import java.util.UUID

@Service
class GetReportPreregistrationSnapshotServiceImpl(
    private val repository: ReportApplicationSnapshotRepository,
) : GetReportPreregistrationSnapshotService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: UUID,
        cursor: String,
    ): ReportPreregistrationSnapshotPage = repository.readPreregistration(expoId, cursor)
}
