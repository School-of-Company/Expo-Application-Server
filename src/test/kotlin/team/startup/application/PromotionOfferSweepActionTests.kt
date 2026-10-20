package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.service.impl.SweepAction
import team.startup.application.domain.application.service.impl.sweepAction
import java.time.Instant
import java.util.UUID

class PromotionOfferSweepActionTests {
    private val deadline = Instant.parse("2030-01-01T00:00:00Z")

    private fun offer(
        expiresAt: Instant?,
        state: String = "SENT",
    ) = PromotionOffer(
        id = UUID(0, 1),
        target = "PREREGISTER",
        expoId = UUID(0, 2),
        programType = null,
        programId = null,
        channel = null,
        applicationId = null,
        batchId = null,
        sessionId = 1L,
        requestId = "request",
        seats = 1,
        state = state,
        hardDeadline = deadline,
        sendAfter = deadline.minusSeconds(3600),
        eventId = UUID(0, 3),
        attemptId = null,
        providerAcceptedAt = null,
        expiresAt = expiresAt,
        respondedAt = null,
        voidReason = null,
    )

    @Test
    fun `expiry capped at the hard deadline only releases the hold`() {
        assertEquals(SweepAction.VOID_HARD_DEADLINE, sweepAction(offer(deadline), true, deadline))
        assertEquals(SweepAction.VOID_HARD_DEADLINE, sweepAction(offer(deadline), true, deadline.plusSeconds(60)))
    }

    @Test
    fun `expiry before the hard deadline still ends the wait`() {
        val expires = deadline.minusSeconds(600)
        assertEquals(SweepAction.NONE, sweepAction(offer(expires), true, expires.minusSeconds(1)))
        assertEquals(SweepAction.EXPIRE, sweepAction(offer(expires), true, expires))
    }

    @Test
    fun `late sweep after expiry and hard deadline only releases the hold`() {
        val expires = deadline.minusSeconds(600)
        assertEquals(SweepAction.EXPIRE, sweepAction(offer(expires), true, deadline.minusSeconds(1)))
        assertEquals(SweepAction.VOID_HARD_DEADLINE, sweepAction(offer(expires), true, deadline))
        assertEquals(SweepAction.VOID_HARD_DEADLINE, sweepAction(offer(expires), true, deadline.plusSeconds(3600)))
    }

    @Test
    fun `closed target and unsent offers keep their handling`() {
        assertEquals(SweepAction.VOID_TARGET_CLOSED, sweepAction(offer(deadline.minusSeconds(60)), false, deadline))
        assertEquals(SweepAction.VOID_HARD_DEADLINE, sweepAction(offer(null, "SCHEDULED"), true, deadline))
        assertEquals(SweepAction.NONE, sweepAction(offer(null, "SCHEDULED"), true, deadline.minusSeconds(1)))
    }
}
