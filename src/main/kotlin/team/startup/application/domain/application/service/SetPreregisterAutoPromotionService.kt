package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey

data class SetPreregisterAutoPromotionCommand(
    val session: PreregisterSessionKey,
    val enabled: Boolean,
)

interface SetPreregisterAutoPromotionService {
    fun execute(command: SetPreregisterAutoPromotionCommand)
}
