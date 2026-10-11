package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CreateReportApplicationSnapshotRequest
import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import java.util.UUID

interface CreateReportApplicationSnapshotService {
    fun execute(
        expoId: UUID,
        request: CreateReportApplicationSnapshotRequest,
    ): ReportApplicationSnapshotPage
}
