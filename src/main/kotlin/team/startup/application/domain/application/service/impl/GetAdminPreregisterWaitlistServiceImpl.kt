package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlistCommand
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetAdminPreregisterWaitlistService

@Service
class GetAdminPreregisterWaitlistServiceImpl(
    private val sessions: PreregisterSessionRepository,
    private val reservations: PreregisterReservationRepository,
) : GetAdminPreregisterWaitlistService {
    @Transactional(readOnly = true)
    override fun execute(command: AdminPreregisterWaitlistCommand): AdminPreregisterWaitlist {
        sessions.readLock(command.expoId)
        if (sessions.expoDeleted(command.expoId)) throw ResponseStatusException(HttpStatus.CONFLICT, "삭제된 행사입니다.")
        return AdminPreregisterWaitlist(command.expoId, command.sessionId, reservations.adminWaitlist(command.expoId, command.sessionId))
    }
}
