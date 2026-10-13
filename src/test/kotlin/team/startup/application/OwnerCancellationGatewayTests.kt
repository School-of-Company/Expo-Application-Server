package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerCancellationStatus
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.OwnerCancellationGuard
import team.startup.application.domain.application.service.impl.AttendanceCancellationAdapter
import team.startup.application.domain.application.service.impl.CancelOwnerApplicationServiceImpl
import team.startup.application.domain.application.service.impl.OwnerCancellationGateway
import team.startup.application.domain.application.service.impl.PreregisterSessionLedger
import team.startup.application.domain.application.service.impl.PreregisterWaitlist
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.SessionAttendanceOutbox
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID

class OwnerCancellationGatewayTests {
    private val mapper = jacksonObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var gateway: OwnerCancellationGateway
    private val requests = mutableListOf<Pair<String, JsonNode>>()

    @Volatile private var sessionCode = 200

    @Volatile private var statusCode = 200

    @Volatile private var sessionBody = """{"expoId":"$EXPO","phoneNumber":"01012345678"}"""

    @Volatile private var statusBody = VALID

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            assertEquals(null, exchange.requestURI.query)
            val path = exchange.requestURI.path
            requests.add(path to mapper.readTree(exchange.requestBody.bufferedReader().use { it.readText() }))
            val session = path == "/internal/participant-sessions/resolve"
            val bytes = (if (session) sessionBody else statusBody).toByteArray()
            val code = if (session) sessionCode else statusCode
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (code != 204) it.write(bytes) }
        }
        server.start()
        gateway = OwnerCancellationGateway(mapper, "http://127.0.0.1:${server.address.port}", "user-token")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `uses only resolved session phone and read-only status endpoint`() {
        assertEquals(OwnerCancellationStatus(2, false, false, false, null), gateway.find(COMMAND))
        assertEquals(
            listOf("/internal/participant-sessions/resolve", "/internal/standard-participants/cancellation-status"),
            requests.map {
                it.first
            },
        )
        assertEquals(mapper.readTree("""{"expoId":"$EXPO","sessionId":"opaque"}"""), requests[0].second)
        assertEquals(mapper.readTree("""{"expoId":"$EXPO","phoneNumber":"01012345678","participantId":2}"""), requests[1].second)
        assertFalse(COMMAND.toString().contains("opaque"))
        statusBody = """{"participantId":2,"representative":true,"entered":true,"traineeLinked":true,"linkedTraineeId":502}"""
        assertEquals(OwnerCancellationStatus(2, true, true, true, 502), gateway.find(COMMAND))
    }

    @Test
    fun `invalid session or identity cannot reach cancellation status`() {
        failure(HttpStatus.UNAUTHORIZED) { gateway.find(COMMAND.copy(sessionId = " ")) }
        failure(HttpStatus.NOT_FOUND) { gateway.find(COMMAND.copy(participantId = 0)) }
        assertEquals(0, requests.size)
        sessionCode = 401
        failure(HttpStatus.UNAUTHORIZED) { gateway.find(COMMAND) }
        sessionCode = 200
        for (body in listOf(
            "{}",
            "null",
            "[]",
            "{",
            """{"expoId":"${UUID.randomUUID()}","phoneNumber":"010"}""",
            """{"expoId":"$EXPO","phoneNumber":" "}""",
            """{"expoId":"$EXPO","phoneNumber":"${"1".repeat(21)}"}""",
        )) {
            sessionBody = body
            failure(HttpStatus.BAD_GATEWAY) { gateway.find(COMMAND) }
        }
        assertFalse(requests.any { it.first.endsWith("cancellation-status") })
    }

    @Test
    fun `missing malformed or inconsistent status fields never imply no entry or no link`() {
        val malformed =
            listOf(
                "{}",
                "null",
                "[]",
                "{",
                VALID.replace("\"participantId\":2", "\"participantId\":3"),
                VALID.replace("\"participantId\":2", "\"participantId\":9223372036854775808"),
                VALID.replace("\"participantId\":2", "\"participantId\":\"2\""),
                VALID.replace("\"representative\":false", "\"representative\":null"),
                VALID.replace("\"entered\":false", "\"entered\":\"false\""),
                VALID.replace("\"entered\":false,", ""),
                VALID.replace("\"traineeLinked\":false", "\"traineeLinked\":true"),
                VALID.replace("\"linkedTraineeId\":null", "\"linkedTraineeId\":502"),
                VALID.replace(",\"linkedTraineeId\":null", ""),
                VALID.replace("\"traineeLinked\":false,\"linkedTraineeId\":null", "\"traineeLinked\":true,\"linkedTraineeId\":0"),
                VALID.replace(
                    "\"traineeLinked\":false,\"linkedTraineeId\":null",
                    "\"traineeLinked\":true,\"linkedTraineeId\":9223372036854775808",
                ),
            )
        for (body in malformed) {
            statusBody = body
            failure(HttpStatus.BAD_GATEWAY) { gateway.find(COMMAND) }
        }
    }

    @Test
    fun `ownership denial and uncertain provider responses fail closed`() {
        for ((code, expected) in listOf(
            404 to HttpStatus.NOT_FOUND,
            401 to HttpStatus.BAD_GATEWAY,
            400 to HttpStatus.BAD_GATEWAY,
            204 to HttpStatus.BAD_GATEWAY,
            201 to HttpStatus.BAD_GATEWAY,
            409 to HttpStatus.BAD_GATEWAY,
            500 to HttpStatus.SERVICE_UNAVAILABLE,
            503 to HttpStatus.SERVICE_UNAVAILABLE,
        )) {
            statusCode = code
            failure(expected) { gateway.find(COMMAND) }
        }
    }

    @Test
    fun `successful status snapshot and attendance transport cannot replace authoritative deadline guard`() {
        assertFalse(gateway.find(COMMAND).entered)
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("ownerCancellationGateway", gateway)
        val cancellationAdapter = mock(AttendanceCancellationAdapter::class.java)
        beans.registerSingleton("attendanceCancellationAdapter", cancellationAdapter)
        val registration = mock(RegistrationGateway::class.java)
        val owners = mock(OwnerApplicationRepository::class.java)
        val ledger = mock(PreregisterSessionLedger::class.java)
        val outbox = mock(SessionAttendanceOutbox::class.java)
        val command = CancelOwnerApplicationCommand(EXPO, "opaque", OwnerApplicationType.PREREGISTER, "1")
        val party = OwnerApplicationParty(listOf(ManagedOwnerParticipant(2, "Owner", null, true)))
        `when`(registration.managedParty(EXPO, "opaque")).thenReturn(party)
        `when`(owners.cancellationTarget(command, party))
            .thenReturn(OwnerApplicationRepository.CancellationTarget("1", 2, 1, "CONFIRMED"))
        val service =
            CancelOwnerApplicationServiceImpl(
                registration,
                owners,
                mock(PreregisterSessionRepository::class.java),
                mock(ProgramSessionApplicationRepository::class.java),
                mock(TrainingProgramApplicationRepository::class.java),
                ledger,
                outbox,
                mock(CancelProgramSessionService::class.java),
                beans.getBeanProvider(OwnerCancellationGuard::class.java),
                Clock.systemUTC(),
                mock(PreregisterWaitlist::class.java),
            )
        failure(HttpStatus.SERVICE_UNAVAILABLE) { service.execute(command) }
        verifyNoInteractions(ledger, outbox, cancellationAdapter)
    }

    @Test
    fun `missing configuration and transport failure cannot return an absence snapshot`() {
        failure(HttpStatus.SERVICE_UNAVAILABLE) { OwnerCancellationGateway(mapper, "", "user-token").find(COMMAND) }
        failure(
            HttpStatus.SERVICE_UNAVAILABLE,
        ) { OwnerCancellationGateway(mapper, "http://127.0.0.1:${server.address.port}", "").find(COMMAND) }
        assertEquals(0, requests.size)
        server.stop(0)
        failure(HttpStatus.SERVICE_UNAVAILABLE) { gateway.find(COMMAND) }
    }

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val COMMAND = GetOwnerCancellationStatusCommand(EXPO, "opaque", 2)
        private const val VALID =
            """{"participantId":2,"representative":false,"entered":false,""" +
                """"traineeLinked":false,"linkedTraineeId":null}"""
    }
}
