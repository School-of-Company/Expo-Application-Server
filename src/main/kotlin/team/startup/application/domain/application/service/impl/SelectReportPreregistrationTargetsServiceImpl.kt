package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSelection
import team.startup.application.domain.application.presentation.dto.SelectReportPreregistrationTargetsRequest
import team.startup.application.domain.application.service.SelectReportPreregistrationTargetsService
import java.util.UUID

@Service
class SelectReportPreregistrationTargetsServiceImpl(
    private val targets: ReportPreregistrationTargets,
) : SelectReportPreregistrationTargetsService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        request: SelectReportPreregistrationTargetsRequest,
    ): ReportPreregistrationSelection =
        targets.read(expoId, AdminPreregistrationQuery(), request.keys).let { ReportPreregistrationSelection(it, it.size) }
}
