package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.WithdrawAdminPreregisterReservationCommand

interface WithdrawAdminPreregisterReservationService {
    fun execute(command: WithdrawAdminPreregisterReservationCommand)
}
