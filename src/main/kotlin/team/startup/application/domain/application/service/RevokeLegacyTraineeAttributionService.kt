package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ChangeLegacyTraineeAttributionCommand
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult

interface RevokeLegacyTraineeAttributionService {
    fun execute(command: ChangeLegacyTraineeAttributionCommand): LegacyTraineeAttributionResult
}
