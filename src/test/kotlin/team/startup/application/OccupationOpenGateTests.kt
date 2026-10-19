package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import team.startup.application.domain.application.service.impl.OccupationNotOpenException
import team.startup.application.domain.application.service.impl.PreregisterSeatCategory
import java.time.OffsetDateTime

class OccupationOpenGateTests {
    private val openAt = OffsetDateTime.parse("2026-10-26T08:30:00+09:00")
    private val before = openAt.toInstant().minusNanos(1)

    @Test
    fun `only teachers and pre-service teachers apply before the open time and everyone from it`() {
        assertDoesNotThrow { PreregisterSeatCategory.requireOpen(openAt, before, listOf("TEACHER")) }
        assertDoesNotThrow { PreregisterSeatCategory.requireOpen(openAt, before, listOf("TEACHER", "PRE_SERVICE_TEACHER")) }
        val rejected =
            assertThrows<OccupationNotOpenException> {
                PreregisterSeatCategory.requireOpen(openAt, before, listOf("TEACHER", "ELEMENTARY_STUDENT"))
            }
        assertEquals(400, rejected.statusCode.value())
        assertEquals("교원·예비교사 외 유형은 2026-10-26 08:30(KST)부터 신청할 수 있습니다.", rejected.reason)
        assertThrows<OccupationNotOpenException> { PreregisterSeatCategory.requireOpen(openAt, before, listOf(null)) }
        assertDoesNotThrow { PreregisterSeatCategory.requireOpen(openAt, openAt.toInstant(), listOf("GENERAL", null)) }
        assertDoesNotThrow { PreregisterSeatCategory.requireOpen(null, before, listOf("GENERAL", null)) }
    }
}
