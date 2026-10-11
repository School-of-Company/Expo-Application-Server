package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationSort
import team.startup.application.domain.application.presentation.dto.ReportApplicationSelection
import team.startup.application.domain.application.presentation.dto.ReportApplicationTarget
import team.startup.application.domain.application.presentation.dto.ReportRegistrationSource
import team.startup.application.domain.application.presentation.dto.SelectReportApplicationTargetsRequest
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.service.SelectReportApplicationTargetsService
import java.util.UUID

@Service
class SelectReportApplicationTargetsServiceImpl(
    private val repository: AdminApplicationQueryRepository,
) : SelectReportApplicationTargetsService {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun execute(
        expoId: UUID,
        request: SelectReportApplicationTargetsRequest,
    ): ReportApplicationSelection {
        val rows =
            repository.targets(
                expoId,
                AdminApplicationQuery(sort = AdminApplicationSort.CREATED_ASC),
                request.applicationIds.size,
                request.applicationIds,
            )
        if (rows.size != request.applicationIds.size) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "One or more selected applications are unavailable in this expo")
        }
        return ReportApplicationSelection(
            rows.map { ReportApplicationTarget(expoId, it, ReportRegistrationSource(expoId, it.participantId)) },
            rows.size,
        )
    }
}
