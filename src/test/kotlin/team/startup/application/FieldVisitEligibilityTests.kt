package team.startup.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.service.impl.FieldVisitEligibility
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldVisitEligibilityTests {
    @Test
    fun `KST 자정에서 오늘 권위가 바뀌며 옛 FIELD fallback은 권한을 만들지 않는다`() {
        val visits = mock(FieldVisitRegistrationRepository::class.java)
        val expoId = UUID.randomUUID()
        val day = LocalDate.of(2026, 10, 22)
        val registration = FieldVisitRegistration(UUID.randomUUID(), expoId, 42, day, "ACTIVE", 1, Instant.EPOCH, null, null, false)
        `when`(visits.find(expoId, 42, day)).thenReturn(registration)
        val before = FieldVisitEligibility(visits, true, Clock.fixed(Instant.parse("2026-10-21T14:59:59Z"), ZoneOffset.UTC))
        val midnight = FieldVisitEligibility(visits, true, Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC))
        assertFalse(before.current(expoId, 42, true))
        assertEquals(403, assertThrows<ResponseStatusException> { before.current(expoId, 42, true, true) }.statusCode.value())
        assertTrue(midnight.current(expoId, 42, false))
        assertTrue(midnight.hasTodayRegistration(expoId, 42))
        `when`(visits.find(expoId, 42, day)).thenReturn(registration.copy(deliveryPending = true))
        assertFalse(midnight.current(expoId, 42, true))
        `when`(visits.find(expoId, 42, day)).thenReturn(registration.copy(cancellationPending = true))
        assertEquals(409, assertThrows<ResponseStatusException> { midnight.current(expoId, 42, true) }.statusCode.value())
    }
}
