package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.AsyncEvent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.PGConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.service.impl.AdminDataChangeListener
import team.startup.application.domain.application.service.impl.AdminDataChangeStream
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "application.internal-token=test-token",
        "management.health.redis.enabled=false",
        "application.admin-events.enabled=true",
        "application.admin-events.heartbeat-interval=PT1H",
        "application.admin-events.max-connections-per-expo=3",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class AdminDataChangeHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var stream: AdminDataChangeStream

    @Autowired private lateinit var listener: AdminDataChangeListener

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var mapper: ObjectMapper

    private val expo = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        accessStatus.set(200)
        accessRequests.clear()
        jdbc.execute(
            "TRUNCATE tb_program_session_application, tb_preregister_application, tb_preregister_reservation, " +
                "tb_preregister_session_state CASCADE",
        )
        eventually { listener.isListening() }
    }

    @Test
    fun `subscription requires live expo admin read authority in the header and never a query credential`() {
        assertEquals(401, subscribe(authorization = null).response.status)
        accessStatus.set(403)
        assertEquals(403, subscribe().response.status)
        accessStatus.set(200)
        val query =
            mvc
                .perform(
                    get("/application/admin/expos/$expo/events?access_token=jwt").header("Authorization", "Bearer admin"),
                ).andReturn()
        assertEquals(400, query.response.status)
        assertEquals(0, stream.connectionCount())
        val open = subscribe()
        assertTrue(open.request.isAsyncStarted)
        assertEquals("text/event-stream", open.response.contentType?.substringBefore(';'))
        assertEquals("no-store", open.response.getHeader("Cache-Control"))
        assertEquals(listOf("Bearer admin"), accessRequests.takeLast(1))
        assertEquals(1, stream.connectionCount(expo))
    }

    @Test
    fun `committed change reaches both admins of the expo once while another expo stays silent`() {
        val first = subscribe()
        val second = subscribe()
        val outsider = subscribe(other)
        insertProgram(UUID.randomUUID(), expo, 1)
        eventually { events(first).size == 1 && events(second).size == 1 }
        Thread.sleep(300)
        assertEquals(0, events(outsider).size)
        val event = events(first).single()
        assertEquals(setOf("eventId", "expoId", "resource", "occurredAt"), event.propertyNames().toSet())
        assertEquals(expo.toString(), event["expoId"].asString())
        assertEquals("APPLICATIONS", event["resource"].asString())
        assertTrue(first.response.contentAsString.contains("id:${event["eventId"].asString()}"))
    }

    @Test
    fun `rolled back change publishes nothing`() {
        val result = subscribe()
        transactions.executeWithoutResult {
            insertProgram(UUID.randomUUID(), expo, 1)
            it.setRollbackOnly()
        }
        Thread.sleep(1_500)
        assertEquals(0, events(result).size)
        insertProgram(UUID.randomUUID(), expo, 2)
        eventually { events(result).size == 1 }
    }

    @Test
    fun `triggers signal ledger state changes once per transaction and ignore bookkeeping updates`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("LISTEN application_admin_changes") }
            val pg = connection.unwrap(PGConnection::class.java)

            fun drain(): List<String> {
                Thread.sleep(300)
                return pg.getNotifications(200).orEmpty().map { it.parameter }
            }
            drain()
            val application = UUID.randomUUID()
            insertProgram(application, expo, 1)
            assertEquals(listOf(expo.toString()), drain())
            jdbc.update("UPDATE tb_program_session_application SET answers_snapshot = '{\"1\":\"x\"}' WHERE id = ?", application)
            assertEquals(emptyList<String>(), drain())
            jdbc.update("UPDATE tb_program_session_application SET status = 'CONFIRMED' WHERE id = ?", application)
            assertEquals(emptyList<String>(), drain())
            transactions.executeWithoutResult {
                insertProgram(UUID.randomUUID(), expo, 2)
                jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE expo_id = ?", expo)
            }
            assertEquals(listOf(expo.toString()), drain())
            jdbc.update("DELETE FROM tb_program_session_application WHERE id = ?", application)
            assertEquals(listOf(expo.toString()), drain())

            jdbc.update("INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}')", other)
            drain()
            jdbc.update(
                "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) VALUES (?, 1, 1, 1, 'CONFIRMED')",
                other,
            )
            assertEquals(listOf(other.toString()), drain())
            jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE expo_id = ?", other)
            assertEquals(listOf(other.toString()), drain())

            jdbc.update(
                "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants) VALUES (?, 'r1', 1, '{}', '{}', 1)",
                other,
            )
            assertEquals(listOf(other.toString()), drain())
            jdbc.update("UPDATE tb_preregister_reservation SET attempts = attempts + 1, next_attempt_at = now() WHERE expo_id = ?", other)
            assertEquals(emptyList<String>(), drain())
            jdbc.update("UPDATE tb_preregister_reservation SET state = 'REJECTED' WHERE expo_id = ?", other)
            assertEquals(listOf(other.toString()), drain())
        }
        assertEquals(
            listOf("tr_promotion_offer_admin_change", "tr_promotion_offer_admin_hold"),
            jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'tb_promotion_offer'::regclass AND NOT tgisinternal ORDER BY tgname",
                String::class.java,
            ),
        )
    }

    @Test
    fun `another instance listening on the same database receives the change`() {
        val received = CopyOnWriteArrayList<UUID>()
        val otherStream =
            object : AdminDataChangeStream(mapper, Clock.systemUTC(), 10, 10, Duration.ofMinutes(1), Duration.ofMinutes(1)) {
                override fun changed(expoId: UUID) {
                    received.add(expoId)
                }
            }
        val otherInstance = AdminDataChangeListener(dataSource, otherStream)
        otherInstance.start()
        try {
            eventually { otherInstance.isListening() }
            val local = subscribe()
            insertProgram(UUID.randomUUID(), expo, 1)
            eventually { received == listOf(expo) && events(local).size == 1 }
        } finally {
            otherInstance.stop()
            otherStream.destroy()
        }
    }

    @Test
    fun `listener reconnects after losing its database session and asks every subscriber to resync`() {
        val result = subscribe()
        jdbc.queryForList(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query = 'LISTEN application_admin_changes' AND pid <> pg_backend_pid()",
        )
        eventually(seconds = 10) { events(result).size == 1 }
        eventually { listener.isListening() }
        insertProgram(UUID.randomUUID(), expo, 1)
        eventually { events(result).size == 2 }
    }

    @Test
    fun `closed and expired connections are released and per expo limits apply`() {
        val closed = subscribe()
        val expired = subscribe()
        subscribe()
        assertEquals(503, subscribe().response.status)
        (closed.request.asyncContext as MockAsyncContext).complete()
        eventually { stream.connectionCount(expo) == 2 }
        val context = expired.request.asyncContext as MockAsyncContext
        context.listeners.forEach { it.onTimeout(AsyncEvent(context)) }
        eventually { stream.connectionCount(expo) == 1 }
        assertTrue(subscribe().request.isAsyncStarted)
    }

    private fun subscribe(
        expoId: UUID = expo,
        authorization: String? = "Bearer admin",
    ): MvcResult =
        mvc
            .perform(
                get("/application/admin/expos/$expoId/events").apply { if (authorization != null) header("Authorization", authorization) },
            ).andReturn()

    private fun events(result: MvcResult) =
        result.response.contentAsString
            .split("\n\n")
            .filter { it.contains("event:admin-data-changed") }
            .map { block -> mapper.readTree(block.lines().single { it.startsWith("data:") }.removePrefix("data:")) }

    private fun insertProgram(
        id: UUID,
        expoId: UUID,
        participantId: Long,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id,expo_id,program_type,program_id,group_id,participant_id,status,definition_snapshot," +
                "form_id,form_version,questions_snapshot,answers_snapshot) VALUES (?,?,'STANDARD',1,?,?," +
                "'CONFIRMED','{}',?,1,'[]','{}')",
            id,
            expoId,
            UUID.randomUUID(),
            participantId,
            UUID.randomUUID(),
        )
    }

    private fun eventually(
        seconds: Long = 5,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met in time")
            Thread.sleep(20)
        }
    }

    companion object {
        private val accessStatus = AtomicInteger(200)
        private val accessRequests = CopyOnWriteArrayList<String>()
        private val expoServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expo/") { exchange ->
                    accessRequests.add(exchange.requestHeaders.getFirst("Authorization"))
                    val id = exchange.requestURI.path.split("/")[3]
                    val body =
                        """{"expoId":"$id","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}"""
                            .toByteArray()
                    exchange.sendResponseHeaders(accessStatus.get(), body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${expoServer.address.port}" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = expoServer.stop(0)

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
