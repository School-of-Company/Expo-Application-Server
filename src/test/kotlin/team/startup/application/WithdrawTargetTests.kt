package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import team.startup.application.domain.application.service.impl.withdrawTarget
import java.util.UUID

class WithdrawTargetTests {
    private fun row(
        sessionId: Long,
        state: String,
        queueSeq: Long,
    ) = PreregisterReservation(
        expoId = UUID(0, 1),
        requestId = "request-$sessionId",
        sessionId = sessionId,
        command = "REGISTER",
        userRequest =
            ParticipantRegistration(
                expoId = UUID(0, 1).toString(),
                name = "name",
                phoneNumber = "01000000000",
                informationJson = "{}",
                personalInformationStatus = true,
                applicationType = "STANDARD",
                trainingId = null,
                occupation = null,
                school = null,
                requestId = "request-$sessionId",
                formId = null,
                questions = null,
            ),
        applicants = 1,
        state = state,
        result = null,
        errorStatus = null,
        attempts = 0,
        queueSeq = queueSeq,
    )

    // Latest first, as waitlistedByPhone returns them.
    private val rows = listOf(row(2, "RESERVED", 2), row(1, "WAITING", 1))

    @Test
    fun `without a session the older waiting row is picked over a newer reserved one`() {
        assertEquals(1L, withdrawTarget(rows, null)?.sessionId)
    }

    @Test
    fun `without a session and no waiting row the reserved row is still returned so the caller answers 409`() {
        assertEquals("RESERVED", withdrawTarget(listOf(rows[0]), null)?.state)
    }

    @Test
    fun `with a session only that row is returned`() {
        assertEquals("RESERVED", withdrawTarget(rows, 2L)?.state)
        assertEquals("WAITING", withdrawTarget(rows, 1L)?.state)
        assertNull(withdrawTarget(rows, 3L))
        assertNull(withdrawTarget(emptyList(), null))
    }
}
