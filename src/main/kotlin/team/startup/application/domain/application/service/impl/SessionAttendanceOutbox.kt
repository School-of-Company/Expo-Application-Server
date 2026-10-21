package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.repository.SessionAttendanceOutboxRepository
import java.util.UUID

@Component
class SessionAttendanceOutbox(
    private val repository: SessionAttendanceOutboxRepository,
    private val managed: ManagedParticipantRepository,
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
        // #297: 취소 뒤 현장 재등록이면 입장 QR을 다시 연다.
        managed.restoreFieldQr(expoId, participantIds)
    }
}
