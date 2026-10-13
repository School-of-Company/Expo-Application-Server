package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class WaitlistWithdrawnException : ResponseStatusException(HttpStatus.CONFLICT, "WAITLIST_WITHDRAWN")

// Same boundaries as program waitlists (#44): no promotion after the PRE application period, while closed, or once the session starts.
@Component
class PreregisterWaitlist(
    private val reservations: PreregisterReservationRepository,
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val gateway: RegistrationGateway,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ponytail: one daemon thread keeps Expo/Form calls off requests and the shared scheduler; promotions serialize on the Expo lock anyway.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "preregister-waitlist").apply { isDaemon = true } }
    private val queued = ConcurrentHashMap.newKeySet<Pair<UUID, Long>>()

    fun now(): Instant = clock.instant()

    // A party can only wait while promotion is still possible; afterwards it no longer holds the representative's phone.
    fun accepts(definition: PreregisterSessionDefinition): Boolean = now().isBefore(definition.startedAt)

    // Callers run this after their own commit; a failure leaves the queue for the scheduled sweep.
    fun promoteQuietly(
        expoId: UUID,
        sessionId: Long,
    ) {
        val key = expoId to sessionId
        if (!queued.add(key)) return
        executor.execute {
            queued.remove(key)
            try {
                promote(expoId, sessionId)
            } catch (ex: Exception) {
                log.warn("Preregistration promotion deferred with {}", ex.javaClass.simpleName)
            }
        }
    }

    // Waits for promotions queued so far.
    fun flush() {
        executor.submit {}.get()
    }

    fun promote(
        expoId: UUID,
        sessionId: Long,
    ) {
        val blocked =
            ledger.transactions.execute {
                val head = reservations.waitingHead(expoId, sessionId) ?: return@execute true
                // Capacity cannot change once applications exist, so the stored value is enough to skip remote calls.
                val capacity = repository.definition(expoId, sessionId)?.capacity ?: return@execute false
                head.applicants > capacity - repository.occupied(expoId, sessionId)
            }
        if (blocked != false) return
        val observation = ledger.observe(expoId, sessionId)
        val applicationEndedAt = applicationEndedAt(expoId)
        ledger.transactions.executeWithoutResult {
            ledger.lock(expoId)
            if (repository.deleted(expoId, sessionId) || repository.pending(expoId, sessionId) != null) return@executeWithoutResult
            val definition = ledger.current(expoId, sessionId, observation)
            val now = clock.instant()
            if (definition.closed || !now.isBefore(applicationEndedAt) || !now.isBefore(definition.startedAt)) {
                return@executeWithoutResult
            }
            var free = definition.capacity - repository.occupied(expoId, sessionId)
            while (true) {
                // ponytail: strict FIFO, a large head party holds back smaller parties behind it by design (#99 P5).
                val head = reservations.waitingHead(expoId, sessionId) ?: break
                if (head.applicants > free || !reservations.promote(head)) break
                free -= head.applicants
            }
        }
    }

    private fun applicationEndedAt(expoId: UUID): Instant {
        val form =
            gateway.form(expoId.toString(), "STANDARD", "PRE", true)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "사전 등록 폼을 조회할 수 없습니다.")
        return try {
            OffsetDateTime.parse(form.endDate).toInstant()
        } catch (ex: DateTimeParseException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "폼 날짜 응답이 올바르지 않습니다.", ex)
        }
    }
}
