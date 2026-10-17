package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.time.ExpoProgramTime
import java.time.Instant
import java.time.format.DateTimeParseException

class ExpoProgramTimeTests {
    @Test
    fun `Expo local formats use KST and explicit offsets keep their instant`() {
        for (value in listOf(
            "2026-10-20 10:00",
            "2026-10-20T10:00",
            "2026-10-20T10:00:00",
            "2026-10-20T10:00:00+09:00",
            "2026-10-20T01:00:00Z",
            "2026-10-19T20:00:00-05:00",
        )) {
            assertEquals(Instant.parse("2026-10-20T01:00:00Z"), ExpoProgramTime.parse(value).toInstant(), value)
        }
        assertEquals(Instant.parse("2026-10-19T15:00:01Z"), ExpoProgramTime.parse("2026-10-20T00:00:01").toInstant())
    }

    @Test
    fun `display uses one KST day for same day sessions and repeats the date when the end day differs`() {
        assertEquals("10.31(토) 09:30~12:30", ExpoProgramTime.display("2026-10-31T09:30", "2026-10-31T12:30"))
        assertEquals("10.31(토) 09:30 ~ 11.01(일) 16:00", ExpoProgramTime.display("2026-10-31 09:30", "2026-11-01 16:00"))
        assertEquals("10.31(토) 09:30", ExpoProgramTime.display("2026-10-31T09:30", null))
    }

    @Test
    fun `display reads offsetless values as KST and converts UTC values to KST`() {
        assertEquals("10.31(토) 09:30~12:30", ExpoProgramTime.display("2026-10-31T09:30:00", "2026-10-31T12:30:00"))
        assertEquals("10.31(토) 09:30~12:30", ExpoProgramTime.display("2026-10-31T00:30:00Z", "2026-10-31T03:30:00Z"))
        assertEquals("10.31(토) 00:30", ExpoProgramTime.display("2026-10-30T15:30:00Z", null))
        assertEquals("10.31(토) 23:00 ~ 11.01(일) 01:00", ExpoProgramTime.display("2026-10-31T14:00:00Z", "2026-10-31T16:00:00Z"))
    }

    @Test
    fun `invalid dates and incomplete times are rejected`() {
        for (value in listOf("", "invalid", "2026-10-20", "2026-02-30 10:00", "2026-02-30T10:00", "2026-10-20 24:00")) {
            assertThrows(DateTimeParseException::class.java) { ExpoProgramTime.parse(value) }
        }
    }
}
