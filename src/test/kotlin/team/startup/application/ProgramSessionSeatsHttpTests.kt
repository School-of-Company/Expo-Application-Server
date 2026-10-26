package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ProgramAdmissionSeats
import team.startup.application.domain.application.presentation.dto.ProgramCapacityInfo
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.program-sessions.enabled=true"])
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProgramSessionSeatsHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var ledger: ProgramSessionApplicationRepository

    @Autowired private lateinit var waitlist: ProgramWaitlistRepository

    private val path = "/application/expos/$expo/program-sessions/STANDARD/seats"

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE tb_standard_program_application, tb_training_program_application, tb_program_session_application CASCADE")
        jdbc.execute("TRUNCATE tb_program_session_state, tb_preregister_application, tb_preregister_session_state CASCADE")
        expoRequests.clear()
        sessions =
            (1L..SESSIONS).map { id ->
                when (id) {
                    // 사전·현장 정원이 나뉜 회차
                    2L -> {
                        session(id, policy(5, 2, """"preRegistrationCapacity":2,"onsiteCapacity":3"""))
                    }

                    // 정책 없는 회차는 신청 대상이 아니어서 응답에서 빠진다.
                    3L -> {
                        session(id, null)
                    }

                    // 직군별 정원 회차: 교사 숫자가 드러나지 않도록 channels는 싣지 않는다.
                    4L -> {
                        session(
                            id,
                            policy(
                                4,
                                2,
                                """"channels":[{"name":"TEACHER","allowedOccupations":["TEACHER"],"capacity":3,"waitingCapacity":1},{"name":"OTHER","allowedOccupations":["STUDENT"],"capacity":1,"waitingCapacity":1}]""",
                            ),
                        )
                    }

                    5L -> {
                        session(id, policy(5, 0, "").replace("\"closed\":false", "\"closed\":true"))
                    }

                    else -> {
                        session(id, policy(5, 3, ""))
                    }
                }
            }
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{\"startedAt\":\"2000-01-01T00:00:00Z\",\"endedAt\":\"2100-01-01T00:00:00Z\"}')",
            expo,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) VALUES (?, 1, 10, 10, 'CONFIRMED')",
            expo,
        )
        // 회차 1: 확정 2, 사전등록 확정자의 대기 1(셈), 사전등록 없는 대기 1(waitingCount와 같이 세지 않음)
        legacy(1, 1)
        legacy(1, 2)
        application(1, 10, "WAITING")
        application(1, 11, "WAITING")
        // 회차 2: 사전 1, 현장 1, 경로 미상 1, 참여 제안 1석
        legacy(2, 3)
        legacy(2, 4)
        legacy(2, 5)
        application(2, 3, "CONFIRMED", "PRE_REGISTRATION")
        application(2, 4, "CONFIRMED", "ONSITE")
        offer(2, application(2, 10, "WAITING", "PRE_REGISTRATION"))
        // 회차 4: 교사 채널 확정 2, 대기 1
        legacy(4, 6)
        legacy(4, 7)
        application(4, 6, "CONFIRMED", channel = "TEACHER")
        application(4, 7, "CONFIRMED", channel = "TEACHER")
        application(4, 10, "WAITING", channel = "TEACHER")
    }

    @Test
    fun `public seats match the participant selection calculation for every session with one Expo call per hundred sessions`() {
        val response =
            mvc
                .perform(get(path))
                .andReturn()
                .response
        assertEquals(200, response.status, response.contentAsString)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(2, expoRequests.size)
        assertTrue(expoRequests.all { it.contains("programType=STANDARD") })
        assertTrue(expoRequests[1].contains("page=1") && expoRequests[1].contains("definitionVersion=v1"))
        val body = mapper.readTree(response.contentAsString)
        val items = body["sessions"]
        assertEquals(SESSIONS - 1, items.size().toLong())
        assertFalse(response.contentAsString.contains("TEACHER"))
        assertFalse(response.contentAsString.contains("channels"))
        val definitions = sessions.map { mapper.readValue(it, ProgramSessionDefinition::class.java) }.filter { it.policy != null }
        definitions.zip(items.toList()).forEach { (definition, item) ->
            val policy = requireNotNull(definition.policy)
            assertEquals(definition.id, item["programId"].asLong())
            val expected =
                ProgramCapacityInfo.of(
                    policy.capacity,
                    ledger.confirmedCount(ProgramSessionType.STANDARD, definition.id),
                    policy.waitingCapacity,
                    waitlist.waitingCount(definition),
                )
            assertEquals(expected, mapper.treeToValue(item["capacity"], ProgramCapacityInfo::class.java), "program ${definition.id}")
            assertEquals(
                ledger.admissionSeats(definition),
                item["admissionSeats"].takeUnless { it.isNull }?.let { mapper.treeToValue(it, ProgramAdmissionSeats::class.java) },
                "program ${definition.id}",
            )
        }
        val first = items[0]["capacity"]
        assertEquals(2, first["confirmedCount"].asInt())
        assertEquals(3, first["remaining"].asInt())
        assertEquals(1, first["waitingCount"].asInt())
        assertEquals(2, first["waitingRemaining"].asInt())
        val split = items[1]["admissionSeats"]
        assertEquals(0, split["preRegistration"]["remaining"].asInt())
        assertEquals(1, split["onsite"]["remaining"].asInt())
        assertEquals(1, split["offeredSeats"].asInt())
        assertNull(items[0]["admissionSeats"].takeUnless { it.isNull })
        assertEquals(4, items[2]["programId"].asInt())
        assertEquals(2, items[2]["capacity"]["confirmedCount"].asInt())
        assertFalse(items[0]["closed"].asBoolean())
        assertTrue(items[3]["closed"].asBoolean())
    }

    @Test
    fun `training seats count training applications only`() {
        sessions = sessions.take(2).map { it.replace("\"programType\":\"STANDARD\"", "\"programType\":\"TRAINING\"") }
        listOf(501L, 502L, 503L).forEach {
            jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (?, 1)", it)
        }
        application(1, 10, "WAITING", type = "TRAINING")
        val response =
            mvc
                .perform(get(path.replace("STANDARD", "TRAINING")))
                .andReturn()
                .response
        assertEquals(200, response.status, response.contentAsString)
        val items = mapper.readTree(response.contentAsString)["sessions"]
        assertEquals(2, items.size())
        assertTrue(expoRequests.single().contains("programType=TRAINING"))
        sessions.map { mapper.readValue(it, ProgramSessionDefinition::class.java) }.zip(items.toList()).forEach { (definition, item) ->
            val policy = requireNotNull(definition.policy)
            assertEquals(
                ProgramCapacityInfo.of(
                    policy.capacity,
                    ledger.confirmedCount(ProgramSessionType.TRAINING, definition.id),
                    policy.waitingCapacity,
                    waitlist.waitingCount(definition),
                ),
                mapper.treeToValue(item["capacity"], ProgramCapacityInfo::class.java),
            )
        }
        assertEquals(3, items[0]["capacity"]["confirmedCount"].asInt())
        assertEquals(1, items[0]["capacity"]["waitingCount"].asInt())
        assertEquals(0, items[1]["capacity"]["confirmedCount"].asInt())
    }

    @Test
    fun `deleted or unapproved stored definition is a conflict and bad type is rejected`() {
        jdbc.update(
            "INSERT INTO tb_program_session_state (expo_id, program_type, program_id, definition, deleted) VALUES (?, 'STANDARD', 1, CAST(? AS jsonb), true)",
            expo,
            sessions[0],
        )
        assertEquals(
            409,
            mvc
                .perform(get(path))
                .andReturn()
                .response.status,
        )
        assertEquals(
            400,
            mvc
                .perform(get(path.replace("STANDARD", "OTHER")))
                .andReturn()
                .response.status,
        )
    }

    private fun policy(
        capacity: Int,
        waiting: Int,
        extra: String,
    ) = """{"formId":"00000000-0000-0000-0000-0000000000f1","allowedOccupations":["TEACHER","STUDENT"],""" +
        """"capacity":$capacity,"waitingCapacity":$waiting,"applicationStartedAt":"2020-01-01T00:00:00Z",""" +
        """"applicationEndedAt":"2100-01-01T00:00:00Z","cancelEndedAt":"2100-01-01T00:00:00Z",""" +
        """"promotionEndedAt":"2100-01-01T00:00:00Z","closed":false${if (extra.isEmpty()) "" else ",$extra"}}"""

    private fun session(
        id: Long,
        policy: String?,
    ) = """{"expoId":"$expo","programType":"STANDARD","id":$id,"title":"Session $id",""" +
        """"startedAt":"2030-01-01T09:00","endedAt":"2030-01-01T10:00","groupId":"${UUID(0, id)}","revision":1,"policy":$policy}"""

    private fun legacy(
        program: Long,
        participant: Long,
    ) {
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (?, ?)", participant, program)
    }

    private fun application(
        program: Long,
        participant: Long,
        status: String,
        route: String? = null,
        channel: String? = null,
        type: String = "STANDARD",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, trainee_id, status, admission_route, channel, " +
                "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, 1, '[]', '{}')",
            id,
            expo,
            type,
            program,
            UUID.randomUUID(),
            participant,
            participant,
            status,
            route,
            channel,
            UUID.randomUUID(),
        )
        return id
    }

    private fun offer(
        program: Long,
        application: UUID,
    ) {
        jdbc.update(
            "INSERT INTO tb_promotion_offer " +
                "(id, target, expo_id, program_type, program_id, application_id, seats, hard_deadline, send_after, event_id) " +
                "VALUES (?, 'PROGRAM', ?, 'STANDARD', ?, ?, 1, now() + interval '1 day', now(), ?)",
            UUID.randomUUID(),
            expo,
            program,
            application,
            UUID.randomUUID(),
        )
    }

    companion object {
        private const val SESSIONS = 150L
        private val expo: UUID = UUID.randomUUID()
        private var sessions = emptyList<String>()
        private val expoRequests = CopyOnWriteArrayList<String>()
        private val expoServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expo/") { exchange ->
                    assertEquals("expo-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    expoRequests.add(query)
                    val parameters = query.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
                    val page = parameters.getValue("page").toInt()
                    val size = parameters.getValue("size").toInt()
                    val items = sessions.drop(page * size).take(size)
                    val bytes =
                        """{"expoId":"$expo","items":[${items.joinToString(
                            ",",
                        )}],"page":$page,"size":$size,"totalElements":${sessions.size},"definitionVersion":"v1"}"""
                            .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${expoServer.address.port}" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            expoServer.stop(0)
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
