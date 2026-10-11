package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import team.startup.application.domain.application.repository.ReportApplicationSnapshotRepository
import team.startup.application.domain.application.service.GetReportApplicationSnapshotService
import java.util.UUID

@Service
class GetReportApplicationSnapshotServiceImpl(
    private val repository: ReportApplicationSnapshotRepository,
) : GetReportApplicationSnapshotService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: UUID,
        cursor: String,
    ): ReportApplicationSnapshotPage = repository.read(expoId, cursor)
}
