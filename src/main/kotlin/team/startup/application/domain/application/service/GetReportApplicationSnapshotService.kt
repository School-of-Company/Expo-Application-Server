package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import java.util.UUID

interface GetReportApplicationSnapshotService {
    fun execute(
        expoId: UUID,
        cursor: String,
    ): ReportApplicationSnapshotPage
}
