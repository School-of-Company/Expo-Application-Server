package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterReservationKey

interface RetryPreregisterReservationService {
    fun execute(command: PreregisterReservationKey)
}
