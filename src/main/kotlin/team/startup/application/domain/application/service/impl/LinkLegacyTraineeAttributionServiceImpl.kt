package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ChangeLegacyTraineeAttributionCommand
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult
import team.startup.application.domain.application.service.LinkLegacyTraineeAttributionService

@Service
class LinkLegacyTraineeAttributionServiceImpl(
    private val workflow: LegacyTraineeAttributionWorkflow,
) : LinkLegacyTraineeAttributionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: ChangeLegacyTraineeAttributionCommand): LegacyTraineeAttributionResult =
        workflow.change(command, revoke = false)
}
