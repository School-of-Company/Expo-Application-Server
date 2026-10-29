package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class OwnerApplicationsReuseHttpTests {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mvc: MockMvc

    @Test
    fun `GET reuses definitions and resolved phone but checks each participant and refreshes the next request`() {
        for (scenario in listOf("shared", "distinct", "field")) {
            jdbc.execute("TRUNCATE tb_program_session_application, tb_field_visit_registration RESTART IDENTITY CASCADE")
            for (participant in 1..5) {
                for (application in 1..2) {
                    val programId = if (scenario == "distinct") (participant - 1) * 2 + application else application
                    jdbc.update(
                        "INSERT INTO tb_program_session_application " +
                            "(id,expo_id,program_type,program_id,group_id,participant_id,status,definition_snapshot," +
                            "form_id,form_version,questions_snapshot,answers_snapshot) " +
                            "VALUES (?,?,'STANDARD',?,?,?,'CONFIRMED','{}',?,1,'[]','{}')",
                        UUID.randomUUID(),
                        EXPO,
                        programId,
                        UUID(0, programId.toLong()),
                        participant,
                        UUID.randomUUID(),
                    )
                }
                if (scenario == "field") {
                    jdbc.update(
                        "INSERT INTO tb_field_visit_registration " +
                            "(id,expo_id,participant_id,visit_date,status,generation,registered_at,delivery_pending) " +
                            "VALUES (?,?,?,CURRENT_DATE,'ACTIVE',1,CURRENT_TIMESTAMP,false)",
                        UUID.randomUUID(),
                        EXPO,
                        participant,
                    )
                }
            }
            for (expired in listOf(false, true)) {
                deadline = if (expired) Instant.EPOCH else END
                requests.clear()
                val response =
                    mvc
                        .perform(get("/application/expos/$EXPO/my-applications").cookie(Cookie("PARTICIPANT_SESSION", "owner")))
                        .andReturn()
                        .response
                assertEquals(200, response.status, response.contentAsString)
                val body = mapper.readTree(response.contentAsString)
                assertEquals(5, body.path("participants").size())
                assertEquals(10, body.path("applications").size())
                assertEquals(if (scenario == "field") 5 else 0, body.path("fieldRegistrations").size())
                assertTrue(body.path("applications").all { it.path("cancellable").asBoolean() == !expired })
                if (expired) {
                    assertTrue(
                        body.path("applications").all {
                            it.path("cancellationBlockedReason").asString() ==
                                "DEADLINE_EXPIRED"
                        },
                    )
                }
                assertFalse(response.contentAsString.contains("phoneNumber"))
                assertEquals(1, requests.count { it.endsWith("participant-sessions/resolve") })
                assertEquals(1, requests.count { it.endsWith("/managed") })
                assertEquals(if (scenario == "distinct") 10 else 2, requests.count { it.contains("/standard-programs/") })
                assertEquals(
                    if (scenario ==
                        "field"
                    ) {
                        10
                    } else {
                        5
                    },
                    requests.count { it == "/internal/standard-participants/cancellation-status" },
                )
                for (participant in 1..5) {
                    assertEquals(1, requests.count { it.endsWith("/participants/$participant/cancellation-status") })
                    assertEquals(
                        if (scenario ==
                            "field"
                        ) {
                            1
                        } else {
                            0
                        },
                        requests.count { it.contains("/participants/$participant/field-visits/") },
                    )
                }
                assertEquals(
                    when (scenario) {
                        "distinct" -> 22
                        "field" -> 24
                        else -> 14
                    },
                    requests.size,
                )
                assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application", Int::class.java))
            }
        }
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000344")
        private val END = Instant.parse("2100-01-01T00:00:00Z")
        private val mapper = jacksonObjectMapper()
        private val requests = CopyOnWriteArrayList<String>()

        @Volatile private var deadline = END

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    requests.add(path)
                    val text = exchange.requestBody.bufferedReader().use { it.readText() }
                    val body = mapper.readTree(text.ifEmpty { "{}" })
                    val participant =
                        if (path.contains("/participants/")) {
                            path.substringAfter("/participants/").substringBefore('/').toInt()
                        } else {
                            body.path("participantId").asInt(1)
                        }
                    val status =
                        """"participantId":$participant,"representative":${participant == 1},"entered":false,"traineeLinked":false,"linkedTraineeId":null"""
                    val response =
                        when {
                            path.endsWith("participant-sessions/resolve") -> {
                                """{"expoId":"$EXPO","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            path.endsWith("/managed") -> {
                                val participants =
                                    (1..5).joinToString(",") {
                                        """{"participantId":$it,"name":"Person$it","occupation":"TEACHER","representative":${it == 1},"applicationType":"FIELD"}"""
                                    }
                                """{"registered":true,"participants":[$participants]}"""
                            }

                            path.contains("/standard-programs/") -> {
                                val id = path.substringAfterLast('/').toLong()
                                mapper.writeValueAsString(
                                    ProgramSessionDefinition(
                                        EXPO.toString(),
                                        ProgramSessionType.STANDARD,
                                        id,
                                        UUID(0, id),
                                        1,
                                        ProgramSessionPolicy(
                                            UUID(0, 2),
                                            setOf("TEACHER"),
                                            100,
                                            10,
                                            Instant.EPOCH,
                                            END,
                                            false,
                                            deadline,
                                            END,
                                        ),
                                        place = "Hall",
                                    ),
                                )
                            }

                            path.contains("/field-visits/") -> {
                                """{"registrationId":"${path.substringAfter(
                                    "/field-visits/",
                                ).substringBefore(
                                    '/',
                                )}","participantId":$participant,"visitDate":"${body.path(
                                    "visitDate",
                                ).asString()}","generation":1,"enteredOnVisitDate":false,"programAttended":false,"guardState":"NONE","linkedTraineeId":null}"""
                            }

                            path.endsWith("/cancellation-status") -> {
                                if (path.contains("/participants/")) "{$status,\"programAttended\":false}" else "{$status}"
                            }

                            else -> {
                                error("Unexpected downstream path: $path")
                            }
                        }
                    val bytes = response.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @AfterAll @JvmStatic
        fun stop() = server.stop(0)

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            for (key in listOf(
                "application.program-sessions.enabled",
                "application.attendance.enabled",
                "application.field-visits.enabled",
            )) {
                registry.add(key) { true }
            }
            for (key in listOf("application.registration.expo-url", "application.registration.user-url", "application.attendance.url")) {
                registry.add(key) { "http://127.0.0.1:${server.address.port}" }
            }
            for (key in listOf(
                "application.registration.expo-internal-token",
                "application.registration.user-internal-token",
                "application.attendance.internal-token",
            )) {
                registry.add(key) { "test-token" }
            }
        }
    }
}
