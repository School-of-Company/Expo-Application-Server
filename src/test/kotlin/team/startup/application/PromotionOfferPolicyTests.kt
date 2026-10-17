package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.service.impl.PromotionOfferPolicy
import java.time.Instant

class PromotionOfferPolicyTests {
    private val policy = PromotionOfferPolicy(true, "https://client.example/application/forms/{expoId}")

    private fun kst(value: String): Instant =
        java.time.OffsetDateTime
            .parse("$value+09:00")
            .toInstant()

    @Test
    fun `offers start at 00 KST on the day before the session date and disabled keeps automatic promotion`() {
        val start = kst("2030-01-01T00:30:00")
        assertEquals(kst("2029-12-31T00:00:00"), policy.switchAt(start))
        assertFalse(policy.offerMode(start, kst("2029-12-30T23:59:59.999999")))
        assertTrue(policy.offerMode(start, kst("2029-12-31T00:00:00")))
        assertTrue(policy.offerMode(null, kst("2020-01-01T00:00:00")))
        assertFalse(PromotionOfferPolicy(false, "").offerMode(start, kst("2029-12-31T12:00:00")))
        assertEquals(kst("2030-01-01T11:00:00"), policy.programStart("2030-01-01 11:00"))
        assertEquals(null, policy.programStart("not a time"))
    }

    @Test
    fun `send window is 09 inclusive to 18 exclusive KST and night vacancies wait for the next 09`() {
        assertFalse(policy.inWindow(kst("2029-12-31T08:59:59.999999")))
        assertTrue(policy.inWindow(kst("2029-12-31T09:00:00")))
        assertTrue(policy.inWindow(kst("2029-12-31T17:59:59.999999")))
        assertFalse(policy.inWindow(kst("2029-12-31T18:00:00")))
        assertEquals(kst("2029-12-31T09:00:00"), policy.sendAfter(kst("2029-12-31T00:00:00")))
        assertEquals(kst("2029-12-31T12:34:00"), policy.sendAfter(kst("2029-12-31T12:34:00")))
        assertEquals(kst("2030-01-01T09:00:00"), policy.sendAfter(kst("2029-12-31T18:00:00")))
        assertEquals(kst("2029-12-31T18:00:00"), policy.windowEnd(kst("2029-12-31T10:00:00")))
    }

    @Test
    fun `response deadline is provider acceptance plus six hours across midnight but never past the hard cutoff`() {
        val accepted = kst("2029-12-31T17:59:00")
        assertEquals(kst("2029-12-31T23:59:00"), policy.expiresAt(accepted, kst("2030-01-01T09:00:00")))
        assertEquals(kst("2029-12-31T20:00:00"), policy.expiresAt(accepted, kst("2029-12-31T20:00:00")))
        assertEquals(
            "https://client.example/application/forms/00000000-0000-0000-0000-000000000001",
            policy.responseUrl(java.util.UUID(0, 1)),
        )
    }

    @Test
    fun `enabling offers requires an https response page`() {
        assertThrows(IllegalArgumentException::class.java) { PromotionOfferPolicy(true, "") }
        assertThrows(IllegalArgumentException::class.java) { PromotionOfferPolicy(true, "http://client.example/{expoId}") }
    }
}
