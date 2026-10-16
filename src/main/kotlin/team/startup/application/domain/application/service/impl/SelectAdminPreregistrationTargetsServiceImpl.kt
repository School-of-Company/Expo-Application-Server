package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationScope
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargets
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationTargetsCommand
import team.startup.application.domain.application.service.SelectAdminPreregistrationTargetsService

@Service
class SelectAdminPreregistrationTargetsServiceImpl(
    private val reader: AdminPreregistrationReader,
) : SelectAdminPreregistrationTargetsService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AdminPreregistrationTargetsCommand): AdminPreregistrationTargets {
        val (expoId, request) = command
        val (rows, total) =
            when (request.kind) {
                AdminPreregistrationScope.SELECTED -> {
                    reader.read(
                        expoId,
                        AdminPreregistrationQuery(),
                        5001,
                        keys = requireNotNull(request.keys),
                    )
                }

                AdminPreregistrationScope.FILTERED -> {
                    reader.read(expoId, requireNotNull(request.filter), 5001)
                }
            }
        return AdminPreregistrationTargets(expoId = expoId, items = rows, totalElements = total)
    }
}
