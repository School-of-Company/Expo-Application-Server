package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand

interface GetOwnerPreregisterWaitlistService {
    fun execute(command: OwnerPreregisterWaitlistCommand): OwnerPreregisterWaitlist
}
