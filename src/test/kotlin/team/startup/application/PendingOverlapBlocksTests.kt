package team.startup.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.repository.blocks
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import team.startup.application.domain.application.service.impl.ParticipantRegistrationResult
import java.util.UUID

class PendingOverlapBlocksTests {
    private fun reservation(result: ParticipantRegistrationResult?) =
        PreregisterReservation(
            expoId = UUID(0, 1),
            requestId = "request",
            sessionId = 1,
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
                    requestId = "request",
                    formId = null,
                    questions = null,
                ),
            applicants = 1,
            state = "WAITING",
            result = result,
            errorStatus = null,
            attempts = 0,
            queueSeq = 1,
        )

    @Test
    fun `a reservation without known attendees blocks by phone alone`() {
        assertTrue(reservation(null).blocks(listOf(5L)))
        assertTrue(reservation(null).blocks(null))
    }

    @Test
    fun `a session addition blocks only when its attendees overlap the asked ones`() {
        val addition = reservation(ParticipantRegistrationResult(10, "01000000000", listOf(10L, 11L)))
        assertTrue(addition.blocks(listOf(11L, 12L)))
        assertFalse(addition.blocks(listOf(12L)))
        assertTrue(addition.blocks(null))
    }
}
