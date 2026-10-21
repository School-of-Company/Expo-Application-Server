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

    // #198: a cancellation releases only its session; the participant may keep other sessions.
    @Transactional(propagation = Propagation.MANDATORY)
    fun cancelled(
        expoId: UUID,
        participantId: Long,
        sessionId: Long,
    ): Long {
        require(participantId > 0 && sessionId > 0)
        return repository.append(expoId, participantId, sessionId, cancelled = true)
    }

    // #249, #261: on-site registration of a participant with preregistration history lets them enter without a session.
    @Transactional
    fun fieldRegistered(
        expoId: UUID,
        participantIds: List<Long>,
    ) {
        participantIds.forEach { repository.appendFieldRegistered(expoId, it) }
    }
}
