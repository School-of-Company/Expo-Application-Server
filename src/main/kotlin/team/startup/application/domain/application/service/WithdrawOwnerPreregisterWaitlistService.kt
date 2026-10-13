package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand

interface WithdrawOwnerPreregisterWaitlistService {
    fun execute(command: OwnerPreregisterWaitlistCommand)
}
