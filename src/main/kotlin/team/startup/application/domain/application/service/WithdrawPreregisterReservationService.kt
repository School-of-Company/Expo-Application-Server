package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.WithdrawPreregisterReservationCommand

interface WithdrawPreregisterReservationService {
    fun execute(command: WithdrawPreregisterReservationCommand)
}
