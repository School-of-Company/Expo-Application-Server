package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdoptPreregisterWaitingCapacityCommand

interface AdoptPreregisterWaitingCapacityService {
    fun execute(command: AdoptPreregisterWaitingCapacityCommand)
}
