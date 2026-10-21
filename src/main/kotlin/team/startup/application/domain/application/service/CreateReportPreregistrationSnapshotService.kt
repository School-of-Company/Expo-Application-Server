package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.CreateReportPreregistrationSnapshotRequest
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSnapshotPage
import java.util.UUID

interface CreateReportPreregistrationSnapshotService {
    fun execute(
        expoId: UUID,
        request: CreateReportPreregistrationSnapshotRequest,
    ): ReportPreregistrationSnapshotPage
}
