package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Component
class FieldVisitEligibility(
    private val visits: FieldVisitRegistrationRepository,
    @Value("\${application.field-visits.enabled:false}") private val enabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun current(
        expoId: UUID,
        participantId: Long,
        fallback: Boolean,
        requireToday: Boolean = false,
    ): Boolean {
        if (!enabled) return fallback
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
        val visit = visits.find(expoId, participantId, today)
        if (visit?.cancellationPending == true) throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_CANCELLATION_PENDING")
        val field = visit?.status == "ACTIVE" && !visit.deliveryPending
        if (requireToday && !field && !visits.preregistered(expoId, participantId, today)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "PREREGISTRATION_NOT_CONFIRMED")
        }
        return field
    }

    fun hasTodayRegistration(
        expoId: UUID,
        participantId: Long,
    ): Boolean {
        if (!enabled) return true
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
        val visit = visits.find(expoId, participantId, today)
        if (visit?.cancellationPending == true) return false
        return (visit?.status == "ACTIVE" && !visit.deliveryPending) || visits.preregistered(expoId, participantId, today)
    }
}
