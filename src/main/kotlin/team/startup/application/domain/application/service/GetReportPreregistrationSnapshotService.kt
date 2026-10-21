package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSnapshotPage
import java.util.UUID

interface GetReportPreregistrationSnapshotService {
    fun execute(
        expoId: UUID,
        cursor: String,
    ): ReportPreregistrationSnapshotPage
}
