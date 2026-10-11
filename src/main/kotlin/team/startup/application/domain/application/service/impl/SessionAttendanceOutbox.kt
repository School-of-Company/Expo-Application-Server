package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.SessionAttendanceOutboxRepository
import java.util.UUID

@Component
class SessionAttendanceOutbox(
    private val repository: SessionAttendanceOutboxRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun confirmed(
        expoId: UUID,
        participantId: Long,
        sessionId: Long,
    ) {
        require(participantId > 0 && sessionId > 0)
        repository.append(expoId, participantId, sessionId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelled(
        expoId: UUID,
        participantId: Long,
    ) {
        require(participantId > 0)
        repository.append(expoId, participantId, null)
    }
}
