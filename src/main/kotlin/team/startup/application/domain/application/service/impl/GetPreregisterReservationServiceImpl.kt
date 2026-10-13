package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterReservationKey
import team.startup.application.domain.application.presentation.dto.PreregisterReservationStatus
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.service.GetPreregisterReservationService

@Service
class GetPreregisterReservationServiceImpl(
    private val reservations: PreregisterReservationRepository,
) : GetPreregisterReservationService {
    @Transactional(readOnly = true)
    override fun execute(query: PreregisterReservationKey): PreregisterReservationStatus {
        val saved = reservations.find(query.expoId, query.requestId, "") ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return PreregisterReservationStatus(
            saved.requestId,
            saved.sessionId,
            saved.state,
            saved.applicants,
            saved.userRequest.requestId,
            saved.result?.groupParticipantIds.orEmpty(),
            saved.errorStatus,
            saved.attempts,
            if (saved.state == "WAITING") reservations.position(saved) else null,
            saved.withdrawn,
        )
    }
}
