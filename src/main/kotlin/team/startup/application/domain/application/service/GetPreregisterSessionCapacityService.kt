package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityResponse

interface GetPreregisterSessionCapacityService {
    fun execute(query: PreregisterSessionCapacityQuery): PreregisterSessionCapacityResponse
}
