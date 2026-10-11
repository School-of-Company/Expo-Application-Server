package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ReportApplicationSelection
import team.startup.application.domain.application.presentation.dto.SelectReportApplicationTargetsRequest
import java.util.UUID

interface SelectReportApplicationTargetsService {
    fun execute(
        expoId: UUID,
        request: SelectReportApplicationTargetsRequest,
    ): ReportApplicationSelection
}
