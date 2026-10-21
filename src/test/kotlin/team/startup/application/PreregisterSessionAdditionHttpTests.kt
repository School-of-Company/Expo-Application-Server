package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// #199: 이미 신청한 일행이 참가자만 골라 다른 회차를 더한다. User에는 다시 저장하지 않는다.
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false", "application.registration.enabled=true", "application.attendance.enabled=true",
        "application.registration.waitlist-intake-enabled=true", "application.registration.recovery-delay-ms=3600000",
        "application.registration.waitlist-promotion-delay-ms=3600000", "application.attendance.poll-delay-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PreregisterSessionAdditionHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var recovery: team.startup.application.domain.application.service.impl.PreregisterRecoveryScheduler

    @Autowired private lateinit var waitlist: team.startup.application.domain.application.service.impl.PreregisterWaitlist

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_promotion_offer, tb_preregister_reservation, tb_preregister_count_outbox, tb_session_attendance_outbox, " +
                "tb_preregister_transition, tb_preregister_request, tb_preregister_application, tb_preregister_session_change, " +
                "tb_preregister_session_state, tb_preregister_deleted_expo RESTART IDENTITY CASCADE",
        )
        parties.clear()
        userRequests.clear()
        capacities.clear()
        closed.clear()
        nextId.set(42)
        formId.set(FORM)
        occupationOpenAt.set(null)
    }

    @Test
    fun `selected attendees are confirmed into another session without saving to User again`() {
        assertEquals(201, register(companion("friend")).status)
        val counts = jdbc.queryForObject("SELECT count(*) FROM tb_preregister_count_outbox", Int::class.java)

        val response = addSession(2, listOf(42, 43), key = "add-1")
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(
            mapper.readTree("""{"sessionId":2,"participantIds":[42,43],"status":"CONFIRMED","position":null}"""),
            mapper.readTree(response.contentAsString),
        )
        // QR과 참가자는 그대로이므로 User 저장·인원 집계는 처음 신청 한 번뿐이다.
        assertEquals(1, userRequests.size)
        assertEquals(counts, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_count_outbox", Int::class.java))
        assertEquals(
            listOf("1:42:42:OTHER", "1:42:43:OTHER", "2:42:42:OTHER", "2:42:43:OTHER"),
            applications(),
        )
        assertEquals(
            "USER:42",
            jdbc.queryForObject(
                "SELECT DISTINCT t.actor_type || ':' || t.actor_participant_id FROM tb_preregister_transition t " +
                    "JOIN tb_preregister_application a ON a.id = t.application_id WHERE a.session_id = 2",
                String::class.java,
            ),
        )
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_session_attendance_outbox WHERE session_id = 2 AND NOT cancelled",
                Int::class.java,
            ),
        )
        // 같은 키는 같은 추가를 이어 가고, 같은 키의 다른 내용과 이미 신청한 회차는 거절한다.
        assertEquals(201, addSession(2, listOf(42, 43), key = "add-1").status)
        assertEquals(409, addSession(2, listOf(42), key = "add-1").status)
        assertEquals(409, addSession(2, listOf(42)).status)
        assertEquals(4, applications().size)
    }

    @Test
    fun `before occupationOpenAt non-teacher attendees cannot add a session but a stored addition replays`() {
        assertEquals(201, register(companion("friend")).status)
        assertEquals(201, addSession(2, listOf(42), key = "before").status)
        occupationOpenAt.set(OffsetDateTime.now().plusHours(1).toString())
        // 이미 받은 추가의 멱등 재시도는 막지 않는다.
        assertEquals(201, addSession(2, listOf(42), key = "before").status)
        val rejected = addSession(2, listOf(43))
        assertEquals(400, rejected.status)
        assertEquals("OCCUPATION_NOT_OPEN", mapper.readTree(rejected.contentAsString)["code"].asString())
    }

    @Test
    fun `the representative may stay out and students alone may attend`() {
        assertEquals(
            201,
            register(
                companion("adult"),
                companion("kid", "ELEMENTARY_STUDENT"),
                companion("teen", "MIDDLE_SCHOOL_STUDENT"),
                companion("senior", "HIGH_SCHOOL_STUDENT"),
            ).status,
        )
        // #287: 학생은 각자 휴대폰으로 신청하므로 학년이 달라도 학생만 고른 회차 추가를 받는다.
        val response = addSession(2, listOf(44, 45, 46), key = "students")
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(listOf("2:42:44:OTHER", "2:42:45:OTHER", "2:42:46:OTHER"), applications().filter { it.startsWith("2:") })
        // 학생만 고른 추가도 멱등 재시도와 이미 신청한 회차·시간 겹침 거절은 그대로다.
        assertEquals(201, addSession(2, listOf(44, 45, 46), key = "students").status)
        assertEquals(409, addSession(2, listOf(44)).status)
        assertEquals(409, addSession(3, listOf(45)).status)
        assertEquals(8, applications().size)
        // 접수대장 답변은 처음 신청 답변에서 이번 참석 동행자만 남긴다.
        val answers =
            mapper.readTree(
                jdbc.queryForObject(
                    "SELECT user_request::jsonb ->> 'informationJson' FROM tb_preregister_reservation WHERE session_addition",
                    String::class.java,
                ),
            )
        assertEquals(listOf("kid", "teen", "senior"), answers["companions"].toList().map { it["name"].asString() })
        assertEquals("GENERAL", answers["occupation"].asString())
    }

    @Test
    fun `outsiders, overlapping sessions, closed sessions and a changed form are rejected`() {
        assertEquals(201, register(companion("friend")).status)
        assertEquals(400, addSession(2, listOf(42, 999)).status)
        assertEquals(400, addSession(2, listOf(42, 42)).status)
        assertEquals(400, addSession(2, emptyList()).status)
        // 회차 3은 확정 회차 1과 시간이 일부 겹친다.
        assertEquals(409, addSession(3, listOf(43)).status)
        closed.add(2)
        assertEquals(409, addSession(2, listOf(43)).status)
        closed.clear()

        formId.set(UUID.randomUUID().toString())
        val changed = addSession(2, listOf(43))
        assertEquals(409, changed.status)
        assertEquals(mapper.readTree("""{"status":409,"code":"FORM_CHANGED"}"""), mapper.readTree(changed.contentAsString))
        assertEquals(2, applications().size)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_reservation", Int::class.java))
    }

    @Test
    fun `addition needs a registered party, the participant session and the csrf token`() {
        sessions["owner"] = PHONE
        assertEquals(404, addSession(2, listOf(42)).status)
        val body = mapper.writeValueAsString(mapOf("sessionId" to 2, "participantIds" to listOf(42)))
        val path = "/application/expos/$EXPO/my-applications/preregister-sessions"
        assertEquals(
            403,
            mvc
                .perform(post(path).cookie(Cookie("PARTICIPANT_SESSION", "owner")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `missing seats queue the addition and promotion confirms it without User`() {
        capacities[2] = 1
        assertEquals(201, register(companion("friend")).status)
        val waiting = addSession(2, listOf(42, 43), key = "wait-1")
        assertEquals(202, waiting.status, waiting.contentAsString)
        val body = mapper.readTree(waiting.contentAsString)
        assertEquals("WAITING", body["status"].asString())
        assertEquals(1, body["position"].asInt())
        assertEquals(0, body["participantIds"].size())
        // 대기 중인 같은 회차는 다시 받지 않는다.
        assertEquals(409, addSession(2, listOf(42)).status)

        capacities[2] = 3
        jdbc.update(
            "UPDATE tb_preregister_session_state SET definition = jsonb_set(definition::jsonb, '{capacity}', '3')::text WHERE session_id = 2",
        )
        recovery.promote()
        waitlist.flush()
        recovery.recover()
        assertEquals(1, userRequests.size)
        assertEquals(listOf("2:42:42:OTHER", "2:42:43:OTHER"), applications().filter { it.startsWith("2:") })
        assertEquals(201, addSession(2, listOf(42, 43), key = "wait-1").status)
    }

    private fun applications(): List<String> =
        jdbc.query(
            "SELECT session_id, representative_id, participant_id, seat_category FROM tb_preregister_application " +
                "WHERE status = 'CONFIRMED' ORDER BY session_id, participant_id",
        ) { rs, _ -> "${rs.getLong(1)}:${rs.getLong(2)}:${rs.getLong(3)}:${rs.getString(4)}" }

    private fun register(vararg companions: Map<String, String>): MockHttpServletResponse {
        sessions["owner"] = PHONE
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to "person",
                    "phoneNumber" to PHONE,
                    "personalInformationStatus" to true,
                    "informationJson" to mapper.writeValueAsString(mapOf("companions" to companions.toList(), "occupation" to "GENERAL")),
                    "sessionId" to 1,
                ),
            )
        return mvc
            .perform(post("/application/pre-standard/$EXPO").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn()
            .response
    }

    private fun addSession(
        sessionId: Long,
        participantIds: List<Long>,
        key: String? = null,
    ): MockHttpServletResponse {
        val request =
            post("/application/expos/$EXPO/my-applications/preregister-sessions")
                .with(csrf())
                .cookie(Cookie("PARTICIPANT_SESSION", "owner"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("sessionId" to sessionId, "participantIds" to participantIds)))
        if (key != null) request.header("Idempotency-Key", key)
        return mvc.perform(request).andReturn().response
    }

    private fun companion(
        name: String,
        occupation: String = "GENERAL",
    ) = mapOf("name" to name, "occupation" to occupation, "region" to "OTHER")

    companion object {
        private val EXPO = UUID.fromString("00000000-0000-0000-0000-000000000199")
        private val FORM = "00000000-0000-0000-0000-00000000f199"
        private const val PHONE = "01000000001"
        private val nextId = AtomicLong(42)
        private val formId = AtomicReference(FORM)

        // #209 교원·예비교사 외 유형 오픈 시각. null이면 폼 응답에 싣지 않는다.
        private val occupationOpenAt = AtomicReference<String?>()
        private val capacities = ConcurrentHashMap<Long, Int>()
        private val closed = ConcurrentHashMap.newKeySet<Long>()
        private val sessions = ConcurrentHashMap<String, String>()

        // 번호별 User 일행: (참가자 ID, 이름, 직업). 첫 항목이 대표자다.
        private val parties = ConcurrentHashMap<String, List<Triple<Long, String, String>>>()
        private val userRequests = CopyOnWriteArrayList<String>()
        private val json =
            tools.jackson.module.kotlin
                .jacksonObjectMapper()

        // 회차 1 00–01시, 회차 2 02–03시, 회차 3 00:30–01:30(회차 1과 겹침).
        private val windows =
            mapOf(
                1L to ("2030-01-01T00:00:00Z" to "2030-01-01T01:00:00Z"),
                2L to ("2030-01-01T02:00:00Z" to "2030-01-01T03:00:00Z"),
                3L to ("2030-01-01T00:30:00Z" to "2030-01-01T01:30:00Z"),
            )

        private fun definition(id: Long): String {
            val (startedAt, endedAt) = windows.getValue(id)
            return """{"id":$id,"expoId":"$EXPO","title":"Session $id","startedAt":"$startedAt","endedAt":"$endedAt",""" +
                """"place":"Place","capacity":${capacities[id] ?: 5},"waitingCapacity":5,"closed":${id in closed},""" +
                """"revision":1,"cancelEndedAt":"2030-01-01T00:00:00Z"}"""
        }

        @Synchronized
        private fun saveParticipants(requestBody: String): Pair<Int, String> {
            userRequests.add(requestBody)
            val request = json.readTree(requestBody)
            val phone = request["phoneNumber"].asString()
            val answers = json.readTree(request["informationJson"].asString())
            val people =
                listOf("person" to answers["occupation"].asString()) +
                    answers["companions"].toList().map { it["name"].asString() to it["occupation"].asString() }
            val party = people.map { (name, occupation) -> Triple(nextId.getAndIncrement(), name, occupation) }
            parties[phone] = party
            val ids = party.joinToString(",") { it.first.toString() }
            return 201 to
                """{"participantId":${party.first().first},"phoneNumber":"$phone","participantIds":[$ids],"groupParticipantIds":[$ids]}"""
        }

        private fun occupationOf(id: Long) =
            parties.values
                .flatten()
                .single { it.first == id }
                .third

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/") { exchange ->
                    val path = exchange.requestURI.path
                    val requestBody = exchange.requestBody.bufferedReader().readText()
                    val (status, body) =
                        when {
                            path == "/internal/participant-sessions/resolve" -> {
                                val session = json.readTree(requestBody)["sessionId"].asString()
                                200 to """{"expoId":"$EXPO","phoneNumber":"${sessions[session]}","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            path == "/internal/standard-participants/managed" -> {
                                val party = parties[json.readTree(requestBody)["phoneNumber"].asString()].orEmpty()
                                val people =
                                    party.mapIndexed { index, (id, name, occupation) ->
                                        """{"participantId":$id,"name":"$name","occupation":"$occupation",""" +
                                            """"representative":${index == 0},"applicationType":"PRE"}"""
                                    }
                                200 to """{"registered":${party.isNotEmpty()},"participants":[${people.joinToString(",")}]}"""
                            }

                            path == "/internal/standard-participants/details" -> {
                                val ids = json.readTree(requestBody)["participantIds"].toList().map { it.asLong() }
                                200 to ids.joinToString(",", "[", "]") { """{"participantId":$it,"occupation":"${occupationOf(it)}"}""" }
                            }

                            path == "/internal/standard-participants" -> {
                                saveParticipants(requestBody)
                            }

                            path.endsWith("/preregister-session") || path.contains("/standard-registrations/") ||
                                path.contains("cancellation-guard/") -> {
                                204 to ""
                            }

                            path.contains("/preregister-sessions/") -> {
                                200 to definition(path.substringAfterLast('/').toLong())
                            }

                            path.startsWith("/internal/forms/") -> {
                                val today = OffsetDateTime.now()
                                200 to
                                    """{"id":"${formId.get()}","startDate":"${today.minusDays(1)}","endDate":"${today.plusDays(1)}",""" +
                                    """"dynamicForm":[{"id":1,"title":"companions","formType":"COMPANION","jsonData":{},""" +
                                    """"otherJson":{"maxSelection":4},"dynamicFormType":"DEFAULT"},""" +
                                    """{"id":2,"title":"occupation","formType":"SELECT","jsonData":{},"otherJson":null,""" +
                                    """"dynamicFormType":"OCCUPATION"}]""" +
                                    (occupationOpenAt.get()?.let { ""","occupationOpenAt":"$it"""" } ?: "") + "}"
                            }

                            else -> {
                                val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
                                200 to
                                    """{"startedDay":"$today","finishedDay":"$today","participantPhoneVerificationRequired":false,""" +
                                    """"participantPhoneVerificationRevision":0}"""
                            }
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
                start()
            }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            listOf("expo", "form", "user").forEach {
                registry.add("application.registration.$it-url") { "http://127.0.0.1:${server.address.port}" }
                registry.add("application.registration.$it-internal-token") { "supplier-token" }
            }
            registry.add("application.attendance.url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.attendance.internal-token") { "supplier-token" }
        }
    }
}
