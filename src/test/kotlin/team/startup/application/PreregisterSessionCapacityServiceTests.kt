package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatusCode
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSeatUsage
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.PreregisterTargetSeats
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.impl.GetPreregisterSessionCapacityServiceImpl
import team.startup.application.domain.application.service.impl.PreregisterSessionLedger
import team.startup.application.domain.application.service.impl.RegistrationGateway
import java.time.Instant
import java.util.UUID

class PreregisterSessionCapacityServiceTests {
    private val repository = mock(PreregisterSessionRepository::class.java)
    private val ledger = mock(PreregisterSessionLedger::class.java)
    private val reservations = mock(PreregisterReservationRepository::class.java)
    private val gateway = mock(RegistrationGateway::class.java)
    private val service = GetPreregisterSessionCapacityServiceImpl(repository, ledger, reservations, gateway, true)
    private val expoId = UUID.randomUUID()
    private val query = PreregisterSessionCapacityQuery(expoId, 1, "owner")
    private val definition =
        PreregisterSessionDefinition(
            1,
            expoId,
            "Session",
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T01:00:00Z"),
            "Place",
            10,
            false,
            1,
            waitingCapacity = 5,
            teacherCapacity = 4,
            nonTeacherCapacity = 6,
        )

    @BeforeEach
    fun setup() {
        val manager = mock(PlatformTransactionManager::class.java)
        `when`(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(SimpleTransactionStatus())
        `when`(ledger.transactions).thenReturn(TransactionTemplate(manager))
        `when`(ledger.observe(expoId, 1)).thenReturn(definition)
        `when`(ledger.current(expoId, 1, definition, false)).thenReturn(definition)
        `when`(repository.occupied(expoId, 1)).thenReturn(3)
        `when`(repository.seatUsage(expoId, 1)).thenReturn(AdminPreregisterSeatUsage(1, 2, 0))
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503, 504])
    fun `User server failures keep public seats readable without owner counts`(status: Int) {
        `when`(gateway.managedParty(expoId, "owner")).thenThrow(ResponseStatusException(HttpStatusCode.valueOf(status)))
        val response = service.execute(query)
        assertEquals(PreregisterTargetSeats(true, 4, 4, null), response.targetSeats)
        assertEquals(null, response.remaining)
        assertEquals(null, response.confirmed)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404, 429])
    fun `other User failures retain their status`(status: Int) {
        `when`(gateway.managedParty(expoId, "owner")).thenThrow(ResponseStatusException(HttpStatusCode.valueOf(status)))
        assertEquals(status, assertThrows(ResponseStatusException::class.java) { service.execute(query) }.statusCode.value())
        verifyNoInteractions(reservations)
    }
}
