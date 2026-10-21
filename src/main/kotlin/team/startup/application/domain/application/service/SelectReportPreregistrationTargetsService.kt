package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSelection
import team.startup.application.domain.application.presentation.dto.SelectReportPreregistrationTargetsRequest
import java.util.UUID

interface SelectReportPreregistrationTargetsService {
    fun execute(
        expoId: UUID,
        request: SelectReportPreregistrationTargetsRequest,
    ): ReportPreregistrationSelection
}
