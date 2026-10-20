package team.startup.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import team.startup.application.domain.application.service.impl.ReservationRetryLimit
import java.time.Instant
import java.util.UUID

class ReservationRetryLimitTests {
    private val limit = ReservationRetryLimit(maxAttempts = 40, maxAgeMinutes = 60)
    private val start = Instant.parse("2026-10-20T00:00:00Z")

    private fun reservation(
        attempts: Int,
        windowAttempts: Int = 0,
        startedAt: Instant? = start,
    ) = PreregisterReservation(
        UUID.randomUUID(),
        "request-1",
        1,
        "",
        org.mockito.Mockito.mock(ParticipantRegistration::class.java),
        1,
        "RESERVED",
        null,
        null,
        attempts,
        retryWindowStartedAt = startedAt,
        retryWindowAttempts = windowAttempts,
    )

    @Test
    fun `횟수와 경과 시간이 모두 상한 아래면 계속 재시도한다`() {
        assertFalse(limit.exceeded(reservation(39), start.plusSeconds(3599)))
    }

    @Test
    fun `횟수가 먼저 상한에 닿으면 넘긴다`() {
        assertTrue(limit.exceeded(reservation(40), start.plusSeconds(60)))
    }

    @Test
    fun `경과 시간이 먼저 상한에 닿으면 넘긴다`() {
        assertTrue(limit.exceeded(reservation(3), start.plusSeconds(3600)))
    }

    @Test
    fun `운영자 재시도 뒤에는 창 시작 이후의 횟수만 센다`() {
        assertFalse(limit.exceeded(reservation(50, windowAttempts = 50, startedAt = start.plusSeconds(3000)), start.plusSeconds(3100)))
        assertTrue(limit.exceeded(reservation(90, windowAttempts = 50, startedAt = start.plusSeconds(3000)), start.plusSeconds(3100)))
    }
}
