package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.presentation.dto.PreregisterChangeOperation
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import java.time.Instant
import java.util.UUID

@Component
class PreregisterSessionLedger(
    private val repository: PreregisterSessionRepository,
    private val gateway: PreregisterSessionGateway,
    transactionManager: PlatformTransactionManager,
) {
    val transactions = TransactionTemplate(transactionManager)

    fun lock(expoId: UUID) {
        repository.lock(expoId)
        if (repository.expoDeleted(expoId)) conflict("삭제된 박람회입니다.")
    }

    fun ensureAvailable(
        expoId: UUID,
        sessionId: Long,
    ) {
        if (sessionId <= 0) badSessionRequest("회차 ID가 올바르지 않습니다.")
        if (repository.deleted(expoId, sessionId)) conflict("삭제된 회차입니다.")
        if (repository.pending(expoId, sessionId) != null) conflict("회차 변경 확인이 필요합니다.")
    }

    fun observe(
        expoId: UUID,
        sessionId: Long,
    ): PreregisterSessionDefinition? = gateway.find(expoId, sessionId)

    fun current(
        expoId: UUID,
        sessionId: Long,
        observation: PreregisterSessionDefinition?,
    ): PreregisterSessionDefinition {
        ensureAvailable(expoId, sessionId)
        val remote = observation ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다.")
        val stored = repository.definition(expoId, sessionId)
        if (stored != null && stored != remote) conflict("승인되지 않은 회차 정의 변경입니다.")
        if (stored == null) repository.saveDefinition(remote)
        return remote
    }

    fun reconcile(
        expoId: UUID,
        sessionId: Long,
        remote: PreregisterSessionDefinition?,
    ): Boolean {
        if (sessionId <= 0) badSessionRequest("회차 ID가 올바르지 않습니다.")
        val pending = repository.pending(expoId, sessionId) ?: return true
        val (revision, request) = pending
        return when (request.operation) {
            PreregisterChangeOperation.UPDATE -> {
                if (remote != request.definition) {
                    false
                } else {
                    repository.saveDefinition(requireNotNull(remote))
                    repository.complete(expoId, sessionId, revision, false)
                    true
                }
            }

            PreregisterChangeOperation.DELETE -> {
                if (remote != null) {
                    false
                } else {
                    repository.complete(expoId, sessionId, revision, true)
                    true
                }
            }
        }
    }

    fun promote(
        definition: PreregisterSessionDefinition,
        automatic: Boolean,
    ): List<PreregisterApplicationReceipt> {
        if (automatic && !Instant.now().isBefore(definition.startedAt)) return emptyList()
        val (confirmed, waiting) = repository.counts(definition.expoId, definition.id)
        val available = (definition.capacity - confirmed).coerceAtLeast(0).toInt()
        if (available > 0 && waiting > 0 && definition.closed) {
            conflict("마감 중 승급 정책이 확정되지 않았습니다.")
        }
        return repository.waiting(definition.expoId, definition.id, available).map {
            repository.status(it.id, "CONFIRMED")
            it.copy(status = "CONFIRMED")
        }
    }
}

internal fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)

internal fun badSessionRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
