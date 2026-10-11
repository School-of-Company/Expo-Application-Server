package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterReservationKey
import team.startup.application.domain.application.presentation.dto.PreregisterReservationStatus

interface GetPreregisterReservationService {
    fun execute(query: PreregisterReservationKey): PreregisterReservationStatus
}
