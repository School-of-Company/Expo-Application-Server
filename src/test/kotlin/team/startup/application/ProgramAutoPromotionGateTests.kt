package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import java.time.Instant
import java.util.UUID

class ProgramAutoPromotionGateTests {
    private val start = Instant.parse("2030-01-01T00:00:00Z")
    private val applicationEnd = Instant.parse("2030-01-02T00:00:00Z")
    private val promotionEnd = Instant.parse("2030-01-03T00:00:00Z")

    private fun policy(
        closed: Boolean = false,
        promotionEndedAt: Instant = promotionEnd,
        enabled: Boolean? = null,
        autoEnd: Instant? = null,
        waiting: Int = 1,
    ) = ProgramSessionPolicy(
        formId = UUID(0, 1),
        allowedOccupations = setOf("TEACHER"),
        capacity = 1,
        waitingCapacity = waiting,
        applicationStartedAt = start,
        applicationEndedAt = applicationEnd,
        closed = closed,
        cancelEndedAt = applicationEnd,
        promotionEndedAt = promotionEndedAt,
        autoPromotionEnabled = enabled,
        autoPromotionEndedAt = autoEnd,
    )

    @Test
    fun `automatic promotion runs from the application start until the earlier of both cutoffs and never while closed`() {
        val tick = 1000L
        assertFalse(policy().autoPromotionOpen(start.minusNanos(tick)))
        assertTrue(policy().autoPromotionOpen(start))
        assertTrue(policy().autoPromotionOpen(applicationEnd.minusNanos(tick)))
        assertFalse(policy().autoPromotionOpen(applicationEnd))
        assertFalse(policy().autoPromotionOpen(promotionEnd))
        val earlyPromotionEnd = start.plusSeconds(60)
        assertTrue(policy(promotionEndedAt = earlyPromotionEnd).autoPromotionOpen(earlyPromotionEnd.minusNanos(tick)))
        assertFalse(policy(promotionEndedAt = earlyPromotionEnd).autoPromotionOpen(earlyPromotionEnd))
        assertFalse(policy(closed = true).autoPromotionOpen(start))
    }

    @Test
    fun `stored auto promotion settings stop every automatic path and open manual confirmation until the final deadline`() {
        val tick = 1000L
        val autoEnd = start.plusSeconds(3600)
        val ended = policy(autoEnd = autoEnd)
        assertTrue(ended.autoPromotionOpen(autoEnd.minusNanos(tick)))
        assertFalse(ended.autoPromotionOpen(autoEnd))
        assertEquals("AUTO_PROMOTION_ACTIVE", ended.manualPromotionBlock(autoEnd.minusNanos(tick)))
        assertNull(ended.manualPromotionBlock(autoEnd))
        // Manual confirmation outlives the application period but never the final promotionEndedAt.
        assertNull(ended.manualPromotionBlock(applicationEnd))
        assertNull(ended.manualPromotionBlock(promotionEnd.minusNanos(tick)))
        assertEquals("PROMOTION_ENDED", ended.manualPromotionBlock(promotionEnd))
        assertEquals("PROMOTION_ENDED", policy(closed = true, autoEnd = autoEnd).manualPromotionBlock(autoEnd))
        val off = policy(enabled = false)
        assertFalse(off.autoPromotionOpen(start))
        assertNull(off.manualPromotionBlock(start))
        assertEquals("PROMOTION_ENDED", off.manualPromotionBlock(start.minusNanos(tick)))
        assertEquals("AUTO_PROMOTION_ACTIVE", policy(enabled = true).manualPromotionBlock(applicationEnd))
        assertEquals("AUTO_PROMOTION_ACTIVE", policy().manualPromotionBlock(applicationEnd))

        assertTrue(policy().promotionSettingsValid())
        assertTrue(policy(waiting = 0, enabled = true).promotionSettingsValid())
        assertTrue(policy(autoEnd = start).promotionSettingsValid())
        assertTrue(policy(autoEnd = promotionEnd).promotionSettingsValid())
        assertFalse(policy(autoEnd = start.minusNanos(tick)).promotionSettingsValid())
        assertFalse(policy(autoEnd = promotionEnd.plusNanos(tick)).promotionSettingsValid())
        assertFalse(policy(autoEnd = autoEnd.plusNanos(1)).promotionSettingsValid())
        assertFalse(policy(enabled = false, autoEnd = autoEnd).promotionSettingsValid())
        assertFalse(policy(waiting = 0, enabled = false).promotionSettingsValid())
        assertFalse(policy(waiting = 0, autoEnd = autoEnd).promotionSettingsValid())
    }
}
