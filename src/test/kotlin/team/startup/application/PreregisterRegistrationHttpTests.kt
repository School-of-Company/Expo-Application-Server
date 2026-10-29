package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.impl.PreregisterRecoveryScheduler
import team.startup.application.domain.application.service.impl.PreregisterRegistrationWorkflow
import team.startup.application.domain.application.service.impl.PreregisterSessionLedger
import team.startup.application.domain.application.service.impl.SessionAttendanceScheduler
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false", "application.registration.enabled=true", "application.attendance.enabled=true",
        "application.registration.recovery-delay-ms=3600000", "application.attendance.poll-delay-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PreregisterRegistrationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var recovery: PreregisterRecoveryScheduler

    @Autowired private lateinit var workflow: PreregisterRegistrationWorkflow

    @Autowired private lateinit var attendance: SessionAttendanceScheduler

    @Autowired private lateinit var reservations: PreregisterReservationRepository

    @Autowired private lateinit var repository: PreregisterSessionRepository

    @Autowired private lateinit var ledger: PreregisterSessionLedger

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_preregister_reservation, tb_preregister_count_outbox, tb_session_attendance_outbox, " +
                "tb_preregister_transition, tb_preregister_request, tb_preregister_application, tb_preregister_session_change, " +
                "tb_preregister_session_state, tb_preregister_deleted_expo RESTART IDENTITY CASCADE",
        )
        userRequests.clear()
        attendanceRequests.clear()
        guardRequests.clear()
        guardCommitSessions.clear()
        counts.clear()
        stored.clear()
        userStatus.set(201)
        responseIds.set(0)
        responseBody.set(null)
        capacity.set(5)
        countStatus.set(204)
        upstreamAvailable = true
        userGate.set(null)
        countGate.set(null)
        // 일반 사전등록은 행사 전날까지만 신규 접수한다(#171).
        expoDayOffset.set(1)
        formDayOffset.set(0)
        phoneVerificationRequired = false
        sessionStatus.set(200)
        sessionRequests.clear()
        verifiedSessionPhone = "01012345678"
        userOccupations.clear()
        detailsStatus.set(200)
        targetCapacities.set(null)
        managedParticipants.clear()
        occupationOpenAt.set(null)
    }

    private fun target(
        teacher: Int,
        nonTeacher: Int,
    ) {
        capacity.set(teacher + nonTeacher)
        targetCapacities.set(teacher to nonTeacher)
    }

    private fun capacity(cookie: String?): JsonNode {
        val request = get("/application/expos/$EXPO/preregister-sessions/1/capacity")
        if (cookie != null) request.cookie(Cookie("PARTICIPANT_SESSION", cookie))
        return mapper.readTree(
            mvc
                .perform(request)
                .andReturn()
                .response.contentAsString,
        )
    }

    @Test
    fun `target sessions reserve each category separately and hide teacher numbers before applying`() {
        target(teacher = 1, nonTeacher = 2)
        val before = capacity(null)
        assertTrue(before.path("remaining").isNull && before.path("maxApplicants").isNull && before.path("confirmed").isNull)
        val seats = before.path("targetSeats")
        assertEquals(
            listOf(true, 2, 2),
            listOf(
                seats.path("teacherAvailable").asBoolean(),
                seats.path("nonTeacherRemaining").asInt(),
                seats.path("nonTeacherMaxApplicants").asInt(),
            ),
        )
        assertTrue(seats.path("ownTeacherRemaining").isNull && !seats.has("teacherRemaining"))
        // Free non-teacher seats never take a teacher, so a two-teacher party is rejected although three seats are free.
        assertEquals(409, submit(key = "teachers", people = 2, occupations = listOf("TEACHER"), representative = "TEACHER"))
        assertEquals(0, count("tb_preregister_reservation"))
        assertEquals(201, submit(key = "mixed", people = 2, occupations = listOf("GENERAL"), representative = "PRE_SERVICE_TEACHER"))
        assertEquals(mapOf(42L to "TEACHER", 43L to "OTHER"), seatCategories())
        val after = capacity(null).path("targetSeats")
        assertEquals(listOf(false, 1), listOf(after.path("teacherAvailable").asBoolean(), after.path("nonTeacherRemaining").asInt()))
        assertEquals(409, submit(key = "teacher", phone = "01011111111", representative = "TEACHER"))
        responseBody.set("""{"participantId":50,"phoneNumber":"01022222222","participantIds":[50],"groupParticipantIds":[50]}""")
        assertEquals(201, submit(key = "general", phone = "01022222222", representative = "PARENT"))
        assertEquals(409, submit(key = "full", phone = "01033333333", representative = "GENERAL"))

        // Individual cancellation returns the seat to its original category only.
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 42")
        val cancelled = capacity(null).path("targetSeats")
        assertEquals(listOf(true, 0), listOf(cancelled.path("teacherAvailable").asBoolean(), cancelled.path("nonTeacherRemaining").asInt()))
        assertEquals(409, submit(key = "full", phone = "01033333333", representative = "GENERAL"))
    }

    @Test
    fun `target sessions require a representative occupation and take no waiting party`() {
        target(teacher = 2, nonTeacher = 3)
        assertEquals(400, submit())
        assertEquals(0, count("tb_preregister_reservation"))
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "VALUES (?, 'queued', 1, 'queued', '{}', 1, 'WAITING', 1)",
            EXPO,
        )
        assertEquals(409, submit(representative = "GENERAL"))
    }

    @Test
    fun `concurrent applications never take more teacher seats than configured`() {
        target(teacher = 1, nonTeacher = 9)
        val pool = Executors.newFixedThreadPool(4)
        val statuses =
            (1..4)
                .map { pool.submit<Int> { submit(key = "k$it", phone = "0101000000$it", representative = "TEACHER") } }
                .map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(listOf(201, 409, 409, 409), statuses.sorted())
        assertEquals(
            1L,
            repository
                .adminSummary(EXPO)
                .sessions
                .single()
                .seatUsage.teacher,
        )
    }

    @Test
    fun `only a verified owner with a confirmed teacher seat sees the teacher remaining seats`() {
        target(teacher = 3, nonTeacher = 3)
        assertEquals(201, submit(people = 2, occupations = listOf("GENERAL"), representative = "TEACHER"))
        managedParticipants.addAll(listOf(42L, 43L))
        assertEquals(2, capacity("owner").path("targetSeats").path("ownTeacherRemaining").asInt())
        sessionStatus.set(401)
        assertTrue(capacity("expired").path("targetSeats").path("ownTeacherRemaining").isNull)
        sessionStatus.set(200)
        managedParticipants.clear()
        managedParticipants.add(99L)
        assertTrue(capacity("stranger").path("targetSeats").path("ownTeacherRemaining").isNull)
        assertTrue(capacity(null).path("targetSeats").path("ownTeacherRemaining").isNull)
    }

    private fun submit(
        key: String? = "request",
        people: Int = 1,
        phone: String = "01012345678",
        session: Long = 1,
        cookie: String? = null,
        occupations: List<String> = List(people - 1) { "GENERAL" },
        representative: String? = null,
    ): Int = submitResponse(key, people, phone, session, cookie, occupations, representative).status

    private fun submitResponse(
        key: String? = "request",
        people: Int = 1,
        phone: String = "01012345678",
        session: Long = 1,
        cookie: String? = null,
        occupations: List<String> = List(people - 1) { "GENERAL" },
        representative: String? = null,
    ): org.springframework.mock.web.MockHttpServletResponse {
        val companions =
            occupations.mapIndexed { index, occupation ->
                mapOf("name" to "person${index + 2}", "occupation" to occupation, "region" to "OTHER") +
                    if (occupation in setOf("TEACHER", "PRE_SERVICE_TEACHER")) mapOf("school" to "school") else emptyMap()
            }
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to "person",
                    "phoneNumber" to phone,
                    "personalInformationStatus" to true,
                    "informationJson" to
                        mapper.writeValueAsString(
                            mapOf("companions" to companions) +
                                (representative?.let { mapOf("occupation" to it, "school" to "school") } ?: emptyMap()),
                        ),
                    "sessionId" to session,
                ),
            )
        val request = post("/application/pre-standard/$EXPO").contentType(MediaType.APPLICATION_JSON).content(body)
        if (key != null) request.header("Idempotency-Key", key)
        if (cookie != null) request.cookie(Cookie("PARTICIPANT_SESSION", cookie))
        return mvc.perform(request).andReturn().response
    }

    // #238: 409 본문은 사유 code와 화면 문구를 싣는다.
    private fun assertConflict(
        code: String,
        response: org.springframework.mock.web.MockHttpServletResponse,
    ) {
        assertEquals(409, response.status)
        val body = mapper.readTree(response.contentAsString)
        assertEquals(code, body["code"].asString())
        assertTrue(body["message"].asString().isNotBlank())
    }

    private fun capacity(): JsonNode {
        val response = mvc.perform(get("/application/expos/$EXPO/preregister-sessions/1/capacity")).andReturn().response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        return mapper.readTree(response.contentAsString)
    }

    @Test
    fun `authenticated reservation retries validate sessions and reuse the original verified snapshot`() {
        phoneVerificationRequired = true
        userStatus.set(503)
        assertEquals(503, submit(phone = "01000000001", cookie = "first-session"))
        val original = userRequests.single()
        assertEquals("01012345678", original.path("phoneNumber").stringValue())
        assertEquals(4, capacity().path("remaining").intValue())
        assertFalse(reservations.find(EXPO, "request", "")!!.command.contains("first-session"))
        userStatus.set(201)
        assertEquals(401, submit(cookie = null))
        sessionStatus.set(401)
        assertEquals(401, submit(cookie = "expired-session"))
        assertEquals(1, userRequests.size)
        assertEquals(4, capacity().path("remaining").intValue())
        sessionStatus.set(200)
        assertEquals(201, submit(phone = "01000000002", cookie = "rotated-session"))
        assertEquals(listOf(original, original), userRequests.toList())
        assertEquals(listOf("first-session", "expired-session", "rotated-session"), sessionRequests.toList())
        assertEquals(1, count("tb_preregister_application"))
        assertEquals(1, count("tb_session_attendance_outbox"))
        assertEquals(4, capacity().path("remaining").intValue())
        verifiedSessionPhone = "01099998888"
        assertEquals(409, submit(cookie = "another-owner-session"))
        assertEquals(2, userRequests.size)
        verifiedSessionPhone = "01012345678"
        sessionStatus.set(401)
        assertEquals(401, submit(cookie = "expired-session"))
        assertEquals(2, userRequests.size)
        assertEquals(1, count("tb_preregister_application"))
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun seatCategories(): Map<Long, String?> =
        jdbc
            .query("SELECT participant_id, seat_category FROM tb_preregister_application", { rs, _ -> rs.getLong(1) to rs.getString(2) })
            .toMap()

    @Test
    fun `single category companions keep their reserved seat category without a User lookup`() {
        assertEquals(201, submit(people = 3, occupations = listOf("TEACHER", "PRE_SERVICE_TEACHER")))
        // The form has no occupation question, so the representative stays unknown instead of being guessed.
        assertEquals(mapOf(42L to null, 43L to "TEACHER", 44L to "TEACHER"), seatCategories())
        assertEquals(
            listOf(2, 0),
            jdbc
                .queryForList("SELECT teacher_applicants, other_applicants FROM tb_preregister_reservation")
                .single()
                .values
                .toList(),
        )
        val usage =
            repository
                .adminSummary(EXPO)
                .sessions
                .single()
                .seatUsage
        assertEquals(listOf(2L, 0L, 1L), listOf(usage.teacher, usage.other, usage.unknown))
    }

    @Test
    fun `mixed companions are confirmed by the User occupation of each returned ID`() {
        userOccupations.putAll(mapOf(43L to "GENERAL", 44L to "TEACHER", 45L to "PARENT"))
        assertEquals(201, submit(people = 4, occupations = listOf("TEACHER", "GENERAL", "PARENT")))
        assertEquals(mapOf(42L to null, 43L to "OTHER", 44L to "TEACHER", 45L to "OTHER"), seatCategories())
        // Individual cancellation keeps the stored category, so only that category's usage drops.
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 44")
        val usage =
            repository
                .adminSummary(EXPO)
                .sessions
                .single()
                .seatUsage
        assertEquals(listOf(0L, 2L, 1L), listOf(usage.teacher, usage.other, usage.unknown))
        assertThrows(Exception::class.java) {
            jdbc.update("UPDATE tb_preregister_application SET seat_category = 'OTHER' WHERE participant_id = 44")
        }
    }

    @Test
    fun `reservations stored before V31 stay unknown instead of being classified on recovery`() {
        userStatus.set(503)
        assertEquals(503, submit(people = 3, occupations = listOf("TEACHER", "GENERAL")))
        jdbc.update(
            "UPDATE tb_preregister_reservation SET teacher_applicants = NULL, other_applicants = NULL, next_attempt_at = CURRENT_TIMESTAMP",
        )
        userStatus.set(201)
        recovery.recover()
        assertEquals(mapOf(42L to null, 43L to null, 44L to null), seatCategories())
    }

    @Test
    fun `mismatched or unavailable User occupations keep the reserved seats`() {
        capacity.set(3)
        detailsStatus.set(503)
        assertEquals(503, submit(people = 3, occupations = listOf("TEACHER", "GENERAL")))
        assertEquals(0, count("tb_preregister_application"))
        assertEquals(409, submit(key = "other", phone = "01099999999"))
        val retained = reservations.find(EXPO, "request", "")!!
        assertEquals("RESERVED", retained.state)
        assertTrue(retained.result != null)
        val usage =
            repository
                .adminSummary(EXPO)
                .sessions
                .single()
                .seatUsage
        assertEquals(listOf(1L, 1L, 1L), listOf(usage.teacher, usage.other, usage.unknown))

        detailsStatus.set(200)
        userOccupations.putAll(mapOf(43L to "GENERAL", 44L to "GENERAL"))
        jdbc.update("UPDATE tb_preregister_reservation SET next_attempt_at = CURRENT_TIMESTAMP")
        recovery.recover()
        assertEquals(0, count("tb_preregister_application"))
        assertEquals(502, reservations.find(EXPO, "request", "")!!.errorStatus)
        assertEquals(409, submit(key = "other", phone = "01099999999"))

        userOccupations[44L] = "TEACHER"
        reservations.retryReviewed(reservations.find(EXPO, "request", "")!!)
        recovery.recover()
        assertEquals(mapOf(42L to null, 43L to "OTHER", 44L to "TEACHER"), seatCategories())
    }

    @Test
    fun `added companion SMS IDs do not replace the full party ledger and recovery snapshot`() {
        responseBody.set("""{"participantId":42,"phoneNumber":"01012345678","participantIds":[44],"groupParticipantIds":[42,43,44]}""")
        assertEquals(201, submit(people = 3))
        assertEquals(3, count("tb_preregister_application"))
        assertEquals(3, count("tb_preregister_count_outbox"))
        assertEquals(3, count("tb_session_attendance_outbox"))
        assertEquals(2, capacity().get("remaining").intValue())
        val saved = reservations.find(EXPO, "request", "")!!
        assertEquals(listOf(44L), saved.result!!.participantIds)
        assertEquals(listOf(42L, 43L, 44L), saved.result.groupParticipantIds)
        assertEquals("COMPLETED", saved.state)
        assertEquals(null, saved.errorStatus)
        val status =
            mvc
                .perform(get("/internal/expos/$EXPO/preregister-reservations/request").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response
        assertEquals(200, status.status)
        assertEquals(
            listOf(42L, 43L, 44L),
            mapper
                .readTree(status.contentAsString)
                .get("participantIds")
                .toList()
                .map { it.asLong() },
        )
        assertEquals(201, submit(people = 3))
        assertEquals(1, userRequests.size)
        recovery.recover()
        assertEquals(listOf(42L, 43L, 44L), counts.sorted())
        attendance.dispatch()
        assertEquals(listOf("PUT:42", "PUT:43", "PUT:44"), attendanceRequests.toList())
    }

    @Test
    fun `invalid full party IDs retain reserved seats without count or Attendance delivery`() {
        capacity.set(8)
        for ((index, group) in listOf("[]", "[43,42]", "[42,42]", "[42,0]", "[42,45]", "[\"42\"]", "null", "[1.5]").withIndex()) {
            responseBody.set("""{"participantId":42,"phoneNumber":"01012345678","participantIds":[43],"groupParticipantIds":$group}""")
            assertEquals(502, submit("group-$index", phone = "0100000000$index"))
        }
        assertEquals(0, capacity().get("remaining").intValue())
        assertTrue(reservations.due().isEmpty())
        assertEquals(0, count("tb_preregister_application"))
        assertEquals(0, count("tb_preregister_count_outbox"))
        assertEquals(0, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `before occupationOpenAt only teacher parties register and everyone may from it`() {
        target(5, 5)
        occupationOpenAt.set(OffsetDateTime.now().plusHours(1).toString())
        assertEquals(
            201,
            submit("teachers", people = 2, phone = "01000000002", occupations = listOf("PRE_SERVICE_TEACHER"), representative = "TEACHER"),
        )
        val child =
            submitResponse(
                "child",
                people = 2,
                phone = "01000000003",
                occupations = listOf("ELEMENTARY_STUDENT"),
                representative = "TEACHER",
            )
        assertEquals(400, child.status)
        assertEquals("OCCUPATION_NOT_OPEN", mapper.readTree(child.contentAsString)["code"].asString())
        assertEquals(400, submit("general", phone = "01000000004", representative = "GENERAL"))
        assertEquals(1, userRequests.size)

        occupationOpenAt.set(OffsetDateTime.now().minusMinutes(1).toString())
        responseBody.set("""{"participantId":50,"phoneNumber":"01000000005","participantIds":[50],"groupParticipantIds":[50]}""")
        assertEquals(201, submit("open", phone = "01000000005", representative = "GENERAL"))
    }

    @Test
    fun `PRE accepts before the event within the form period while FIELD still rejects`() {
        expoDayOffset.set(10)
        assertEquals(201, submit())
        assertEquals(1, count("tb_preregister_application"))
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to "person",
                    "phoneNumber" to "01000000002",
                    "personalInformationStatus" to true,
                    "informationJson" to "{}",
                    "trainingId" to "training-1",
                ),
            )
        for (path in listOf("/application/field/standard/$EXPO", "/application/field/$EXPO")) {
            assertEquals(
                400,
                mvc
                    .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn()
                    .response.status,
            )
        }
        assertEquals(1, userRequests.size)
    }

    @Test
    fun `new STANDARD PRE closes at 00_00 KST on the event start day even when the form is still open`() {
        // 폼은 내일까지 열려 있다. 당일·다음 날 신규 접수만 닫히고 전날은 받는다.
        for ((index, offset) in listOf(0, -1).withIndex()) {
            expoDayOffset.set(offset)
            assertEquals(400, submit("closed-$index", phone = "0100000000$index"))
        }
        assertTrue(userRequests.isEmpty())
        assertEquals(0, count("tb_preregister_reservation"))
        expoDayOffset.set(1)
        assertEquals(201, submit())
        assertEquals(1, count("tb_preregister_application"))
    }

    @Test
    fun `PRE before the event still rejects an expired form without saving User`() {
        expoDayOffset.set(10)
        formDayOffset.set(-3)
        assertEquals(400, submit())
        assertTrue(userRequests.isEmpty())
        assertEquals(0, count("tb_preregister_reservation"))
    }

    @Test
    fun `invalid decoded User IDs stop automatic recovery and retain seats until operator repair`() {
        val invalid =
            listOf(
                """{"participantId":42,"phoneNumber":"01012345678","participantIds":[]}""",
                """{"participantId":42,"phoneNumber":"01012345678","participantIds":[0]}""",
                """{"participantId":42,"phoneNumber":"01012345678","participantIds":[-1]}""",
                """{"participantId":42,"phoneNumber":"01012345678","participantIds":[42,42]}""",
                """{"participantId":0,"phoneNumber":"01012345678","participantIds":[43]}""",
            )
        for ((index, response) in invalid.withIndex()) {
            responseBody.set(response)
            assertEquals(502, submit("invalid-$index", phone = "0100000000$index"))
            val status =
                mvc
                    .perform(get("/internal/expos/$EXPO/preregister-reservations/invalid-$index").header("X-Internal-Token", "test-token"))
                    .andReturn()
                    .response
            assertEquals(502, mapper.readTree(status.contentAsString).get("errorStatus").intValue())
            assertEquals(4 - index, capacity().get("remaining").intValue())
        }
        assertEquals(5, userRequests.size)
        assertTrue(reservations.due().isEmpty())
        repeat(2) { recovery.recover() }
        assertEquals(5, userRequests.size)
        assertEquals(0, count("tb_preregister_application"))
        assertEquals(0, count("tb_preregister_count_outbox"))
        assertEquals(0, count("tb_session_attendance_outbox"))
        val original = userRequests.last()
        responseBody.set(null)
        stored.remove(original.get("requestId").stringValue())
        assertEquals(
            204,
            mvc
                .perform(post("/internal/expos/$EXPO/preregister-reservations/invalid-4/retry").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.status,
        )
        assertEquals(original, userRequests.last())
        assertEquals(1, count("tb_preregister_application"))
        assertEquals(0, capacity().get("remaining").intValue())
    }

    @Test
    fun `late delivery failure does not reschedule a reservation already held for operator review`() {
        responseBody.set("""{"participantId":42,"phoneNumber":"01012345678","participantIds":[]}""")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        userGate.set(entered to release)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Int> { submit() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(502, submit())
            userStatus.set(503)
            release.countDown()
            assertEquals(503, first.get(10, TimeUnit.SECONDS))
            assertTrue(reservations.due().isEmpty())
            assertEquals(
                "infinity",
                jdbc.queryForObject("SELECT next_attempt_at::text FROM tb_preregister_reservation", String::class.java),
            )
            assertEquals(4, capacity().get("remaining").intValue())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `null User success stays retryable with original request and reserved seats`() {
        responseBody.set("null")
        assertEquals(502, submit())
        val original = userRequests.single()
        assertEquals(4, capacity().get("remaining").intValue())
        val status =
            mvc
                .perform(get("/internal/expos/$EXPO/preregister-reservations/request").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response
        assertTrue(mapper.readTree(status.contentAsString).get("errorStatus").isNull)
        jdbc.update("UPDATE tb_preregister_reservation SET next_attempt_at = CURRENT_TIMESTAMP")
        recovery.recover()
        assertEquals(2, userRequests.size)
        assertEquals(original, userRequests.last())
        responseBody.set(null)
        stored.remove(original.get("requestId").stringValue())
        assertEquals(201, submit())
        assertEquals(original, userRequests.last())
        assertEquals(1, count("tb_preregister_application"))
    }

    @Test
    fun `operator review retains seats stops background retry and repeats the original User request after repair`() {
        capacity.set(1)
        responseIds.set(2)
        assertEquals(502, submit())
        val original = userRequests.single()
        val path = "/internal/expos/$EXPO/preregister-reservations/request"
        assertEquals(
            401,
            mvc
                .perform(get(path))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(post("$path/retry"))
                .andReturn()
                .response.status,
        )
        val status = mvc.perform(get(path).header("X-Internal-Token", "test-token")).andReturn().response
        assertEquals(200, status.status)
        val review = mapper.readTree(status.contentAsString)
        assertEquals(502, review.get("errorStatus").intValue())
        assertEquals(original.get("requestId").stringValue(), review.get("userRequestId").stringValue())
        assertFalse(review.has("phoneNumber"))
        assertFalse(review.has("userRequest"))
        repeat(2) { recovery.recover() }
        assertEquals(1, userRequests.size)
        assertEquals(
            review.get("attempts").intValue(),
            jdbc.queryForObject(
                "SELECT attempts FROM tb_preregister_reservation WHERE request_id = 'request'",
                Int::class.java,
            ),
        )
        assertEquals(0, capacity().get("remaining").intValue())
        // Emulate an operator repairing the provider's saved idempotent response, not a new User request.
        responseIds.set(0)
        stored.remove(original.get("requestId").stringValue())
        assertEquals(
            204,
            mvc
                .perform(post("$path/retry").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.status,
        )
        assertEquals(original, userRequests.last())
        assertEquals(1, count("tb_preregister_application"))
        assertEquals(
            409,
            mvc
                .perform(post("$path/retry").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `phone index blocks formatted duplicate representatives before User and preserves ambiguous seats`() {
        userStatus.set(0)
        assertEquals(503, submit(phone = "010-1234-5678"))
        assertConflict("PHONE_IN_USE", submitResponse("other", phone = "01012345678"))
        assertEquals(1, userRequests.size)
        ledger.transactions.execute {
            jdbc.execute("SET LOCAL enable_seqscan = off")
            val plan =
                jdbc
                    .queryForList(
                        "EXPLAIN SELECT * FROM tb_preregister_reservation WHERE expo_id = ? AND " +
                            "regexp_replace(user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ?",
                        String::class.java,
                        EXPO,
                        "01012345678",
                    ).joinToString("\n")
            assertTrue(plan.contains("ix_preregister_reservation_phone"), plan)
        }
    }

    @Test
    fun `slow count HTTP leaves the Expo mutation lock and capacity query available`() {
        assertEquals(201, submit())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        countGate.set(entered to release)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sending = pool.submit { recovery.recover() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(4, pool.submit<Int> { capacity().get("remaining").intValue() }.get(2, TimeUnit.SECONDS))
            pool.submit { ledger.transactions.execute { repository.lock(EXPO) } }.get(2, TimeUnit.SECONDS)
            release.countDown()
            sending.get(5, TimeUnit.SECONDS)
            assertEquals(0, count("tb_preregister_count_outbox"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `count delivery stops starting rows at the tick budget and resumes pending rows next tick`() {
        reservations.enqueueCounts(EXPO, listOf(42L, 43L))
        val delivered = mutableListOf<Long>()
        var elapsed = 0L

        recovery.deliverCounts(nanoTime = { elapsed }) { _, participantId ->
            delivered.add(participantId)
            elapsed = TimeUnit.SECONDS.toNanos(5)
        }

        assertEquals(listOf(42L), delivered)
        assertEquals(1, count("tb_preregister_count_outbox"))
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM tb_preregister_count_outbox", Int::class.java))
        recovery.deliverCounts { _, participantId -> delivered.add(participantId) }
        assertEquals(listOf(42L, 43L), delivered)
        assertEquals(0, count("tb_preregister_count_outbox"))
    }

    @Test
    fun `failed count delivery backs off only that row and resumes another Expo next tick`() {
        val anotherExpo = UUID.randomUUID()
        reservations.enqueueCounts(EXPO, listOf(42L))
        reservations.enqueueCounts(anotherExpo, listOf(43L))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val locked =
                pool.submit {
                    ledger.transactions.execute {
                        repository.lock(EXPO)
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val delivered = mutableListOf<Long>()
            recovery.deliverCounts { _, participantId ->
                delivered.add(participantId)
                if (participantId ==
                    42L
                ) {
                    throw org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    )
                }
            }
            assertEquals(listOf(42L), delivered)
            assertEquals(2, count("tb_preregister_count_outbox"))
            assertEquals(
                1,
                jdbc.queryForObject("SELECT attempts FROM tb_preregister_count_outbox WHERE participant_id = 42", Int::class.java),
            )
            assertEquals(
                0,
                jdbc.queryForObject("SELECT attempts FROM tb_preregister_count_outbox WHERE participant_id = 43", Int::class.java),
            )
            recovery.deliverCounts { _, participantId -> delivered.add(participantId) }
            assertEquals(listOf(42L, 43L), delivered)
            assertEquals(1, count("tb_preregister_count_outbox"))
            release.countDown()
            locked.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `capacity readers share a lock while submission waits for all readers`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val reading =
                pool.submit {
                    ledger.transactions.execute {
                        repository.readLock(EXPO)
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(5, pool.submit<Int> { capacity().get("remaining").intValue() }.get(2, TimeUnit.SECONDS))
            val submitting = pool.submit<Int> { submit() }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { submitting.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            reading.get(5, TimeUnit.SECONDS)
            assertEquals(201, submitting.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `capacity is read only caps at five and submission rechecks seats before User`() {
        capacity.set(20)
        val available = capacity()
        assertEquals(20, available.get("remaining").intValue())
        assertEquals(5, available.get("maxApplicants").intValue())
        // Waiting intake is disabled in this class: Expo's waiting capacity is reported but never offered.
        assertEquals(99, available.get("appliedWaitingCapacity").intValue())
        assertEquals(0, available.get("maxWaitingApplicants").intValue())
        assertEquals(0, count("tb_preregister_session_state"))
        capacity.set(2)
        assertEquals(2, capacity().get("maxApplicants").intValue())
        assertEquals(201, submit(people = 2))
        assertEquals(409, submit("another", 1, "01000000002"))
        assertEquals(1, userRequests.size)
        assertEquals(0, capacity().get("remaining").intValue())
        assertEquals(2, count("tb_preregister_application"))
        assertEquals(
            2L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_preregister_application a JOIN tb_preregister_reservation r " +
                    "ON r.expo_id = a.expo_id AND r.request_id = a.reservation_request_id WHERE r.request_id = 'request'",
                Long::class.java,
            ),
        )
        assertEquals(2, count("tb_session_attendance_outbox"))
        attendance.dispatch()
        assertEquals(listOf("PUT:42", "PUT:43"), attendanceRequests.toList())
        recovery.recover()
        assertEquals(listOf(42L, 43L), counts.toList())
    }

    @Test
    fun `whole group is rejected with no User call and no reservation`() {
        capacity.set(2)
        assertConflict("SEATS_UNAVAILABLE", submitResponse(people = 3))
        assertTrue(userRequests.isEmpty())
        assertEquals(0, count("tb_preregister_reservation"))
        assertEquals(0, count("tb_preregister_application"))
    }

    @Test
    fun `lost User response keeps seats and recovers original snapshot after Expo lookup without Form`() {
        capacity.set(2)
        responseBody.set("""{"participantId":42,"phoneNumber":"01012345678","participantIds":[43],"groupParticipantIds":[42,43]}""")
        userStatus.set(0)
        assertEquals(503, submit(people = 2))
        assertEquals(0, capacity().get("remaining").intValue())
        assertEquals(409, submit("other", 1, "01000000002"))
        assertEquals(0, count("tb_preregister_application"))
        val original = userRequests.first()
        assertNotEquals("request", original.get("requestId").stringValue())
        assertEquals(1, stored.size)
        userStatus.set(201)
        upstreamAvailable = false
        assertEquals(503, submit(people = 2))
        assertEquals(1, userRequests.size)
        upstreamAvailable = true
        formDayOffset.set(10)
        // 행사 시작 뒤에도 이미 좌석을 확보한 예약의 복구와 완료 재조회는 신규 접수가 아니다(#171).
        expoDayOffset.set(0)
        assertEquals(201, submit(people = 2))
        assertTrue(userRequests.all { it == original })
        assertEquals(1, stored.size)
        assertEquals(2, count("tb_preregister_application"))
        assertEquals(2, count("tb_session_attendance_outbox"))
        assertEquals(listOf(43L), reservations.find(EXPO, "request", "")!!.result!!.participantIds)
        assertEquals(listOf(42L, 43L), reservations.find(EXPO, "request", "")!!.result!!.groupParticipantIds)
        val requests = userRequests.size
        assertEquals(201, submit(people = 2))
        assertEquals(requests, userRequests.size)
        assertConflict("IDEMPOTENCY_KEY_REUSED", submitResponse(people = 1))
    }

    @Test
    fun `background recovery handles missing optional key and count failure durably`() {
        userStatus.set(0)
        assertEquals(503, submit(null))
        userStatus.set(201)
        countStatus.set(503)
        jdbc.update("UPDATE tb_preregister_reservation SET next_attempt_at = CURRENT_TIMESTAMP")
        recovery.recover()
        assertEquals(1, count("tb_preregister_application"))
        assertEquals(1, count("tb_preregister_count_outbox"))
        assertEquals(1, userRequests.map { it.get("requestId").stringValue() }.distinct().size)
        countStatus.set(204)
        jdbc.update("UPDATE tb_preregister_count_outbox SET next_attempt_at = CURRENT_TIMESTAMP")
        recovery.recover()
        assertEquals(0, count("tb_preregister_count_outbox"))
    }

    @Test
    fun `explicit User rollback response releases seats but invalid successful response retains seats`() {
        capacity.set(1)
        userStatus.set(409)
        assertConflict("PARTY_LIMIT_EXCEEDED", submitResponse())
        assertEquals(1, capacity().get("remaining").intValue())
        assertConflict("PARTY_LIMIT_EXCEEDED", submitResponse())
        assertEquals(1, userRequests.size)
        userStatus.set(201)
        responseIds.set(2)
        assertEquals(502, submit("second", phone = "01000000002"))
        assertEquals(0, capacity().get("remaining").intValue())
        assertEquals(0, count("tb_preregister_application"))
        assertEquals(0, count("tb_session_attendance_outbox"))
        val userCalls = userRequests.size
        jdbc.update("UPDATE tb_preregister_reservation SET next_attempt_at = CURRENT_TIMESTAMP")
        recovery.recover()
        assertEquals(userCalls, userRequests.size)
    }

    @Test
    fun `concurrent last seat has one winner and only winner calls User`() {
        capacity.set(1)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results =
                (1..2).map { index ->
                    pool.submit<Int> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        submit("race$index", phone = "0100000000$index")
                    }
                }
            start.countDown()
            assertEquals(listOf(201, 409), results.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, userRequests.size)
            assertEquals(1, count("tb_preregister_application"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `User validation rejections release seats while retaining legacy gateway error mapping`() {
        capacity.set(1)
        for (status in listOf(400, 422)) {
            userStatus.set(status)
            assertEquals(502, submit("invalid$status"))
            assertEquals(1, capacity().get("remaining").intValue())
            assertEquals(0, count("tb_preregister_application"))
        }
        userStatus.set(201)
        assertEquals(201, submit("valid"))
        assertEquals(1, count("tb_preregister_application"))
    }

    @Test
    fun `concurrent same-key deliveries use one User requestId and commit one enrollment`() {
        capacity.set(1)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results =
                (1..2).map {
                    pool.submit<Int> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        submit("same")
                    }
                }
            start.countDown()
            assertEquals(listOf(201, 201), results.map { it.get(20, TimeUnit.SECONDS) })
            assertEquals(1, userRequests.map { it.get("requestId").stringValue() }.distinct().size)
            assertEquals(1, stored.size)
            assertEquals(1, count("tb_preregister_application"))
            assertEquals(1, count("tb_session_attendance_outbox"))
            assertEquals(1, count("tb_preregister_count_outbox"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `slow User response does not hold the capacity or purge lock and late success cannot resurrect expo`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        userGate.set(entered to release)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val registration = pool.submit<Int> { submit(people = 2) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(3, pool.submit<Int> { capacity().get("remaining").intValue() }.get(2, TimeUnit.SECONDS))
            val purging =
                pool.submit<Int> {
                    mvc
                        .perform(post("/internal/expos/$EXPO/purge").header("X-Internal-Token", "test-token"))
                        .andReturn()
                        .response.status
                }
            assertEquals(204, purging.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(409, registration.get(5, TimeUnit.SECONDS))
            assertEquals(0, count("tb_preregister_application"))
            attendance.dispatch()
            assertEquals(listOf("DELETE:42", "DELETE:43"), attendanceRequests.toList())
        } finally {
            release.countDown()
            userGate.set(null)
            pool.shutdownNow()
        }
    }

    @Test
    fun `cancellation ownership and internal token are enforced and reapplication sends DELETE before PUT`() {
        assertEquals(201, submit(people = 2))
        val ids = jdbc.queryForList("SELECT id FROM tb_preregister_application ORDER BY id", Long::class.java)
        val path = "/internal/expos/$EXPO/preregister-sessions/1/applications"

        fun cancel(
            token: String?,
            representative: Long?,
        ): Int {
            val request =
                delete(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("representativeId" to representative, "applicationIds" to ids)))
            if (token != null) request.header("X-Internal-Token", token)
            return mvc
                .perform(request)
                .andReturn()
                .response.status
        }
        assertEquals(401, cancel(null, 42))
        assertEquals(409, cancel("test-token", 99))
        assertEquals(200, cancel("test-token", 42))
        // Companion first, representative last; each guard is acquired as admin and committed with the DELETE revision.
        assertEquals(
            listOf(
                "PUT:43:admin-cancellation-guard",
                "PUT:42:admin-cancellation-guard",
                "POST:42:cancellation-guard",
                "POST:43:cancellation-guard",
            ),
            guardRequests.toList(),
        )
        // #221: both commits name the cancelled session.
        assertEquals(listOf(1L, 1L), guardCommitSessions.toList())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Int::class.java))
        assertEquals(200, cancel("test-token", 42))
        assertEquals(201, submit("again", people = 2, session = 2))
        attendance.dispatch()
        assertEquals(listOf("PUT:42", "PUT:43", "DELETE:43", "DELETE:42", "PUT:42", "PUT:43"), attendanceRequests.toList())
        assertEquals(200, cancel("test-token", null))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_application WHERE status = 'CONFIRMED'", Int::class.java))
    }

    @Test
    fun `local commit failure after User success keeps reservation and replays same User request`() {
        jdbc.execute(
            "ALTER TABLE tb_preregister_reservation ADD CONSTRAINT reject_completion " +
                "CHECK (request_id <> 'rollback' OR state <> 'COMPLETED')",
        )
        try {
            assertThrows(jakarta.servlet.ServletException::class.java) { submit("rollback") }
        } finally {
            jdbc.execute("ALTER TABLE tb_preregister_reservation DROP CONSTRAINT reject_completion")
        }
        assertEquals(1, count("tb_preregister_reservation"))
        assertEquals(0, count("tb_preregister_application"))
        assertEquals(0, count("tb_session_attendance_outbox"))
        assertEquals(0, count("tb_preregister_count_outbox"))
        assertEquals(4, capacity().get("remaining").intValue())
        workflow.recover(EXPO, "rollback")
        assertEquals(1, stored.size)
        assertEquals(1, userRequests.map { it.get("requestId").stringValue() }.distinct().size)
        assertEquals(1, count("tb_preregister_application"))
        assertEquals(1, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `reserved seats block definition changes and deleted expo recovery cancels saved User participants`() {
        userStatus.set(0)
        assertEquals(503, submit(people = 2))
        val change = """{"operation":"DELETE","definitionChanged":true,"definition":null}"""
        assertEquals(
            409,
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(
                            "/internal/expos/$EXPO/preregister-sessions/1/changes/2",
                        ).header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change),
                ).andReturn()
                .response.status,
        )
        assertEquals(
            204,
            mvc
                .perform(
                    post("/internal/expos/$EXPO/purge")
                        .header("X-Internal-Token", "test-token"),
                ).andReturn()
                .response.status,
        )
        userStatus.set(201)
        jdbc.update("UPDATE tb_preregister_reservation SET next_attempt_at = CURRENT_TIMESTAMP")
        recovery.recover()
        assertEquals(0, count("tb_preregister_application"))
        attendance.dispatch()
        assertEquals(listOf("DELETE:42", "DELETE:43"), attendanceRequests.toList())
        assertEquals(409, submit(people = 2))
        assertTrue(counts.isEmpty())
    }

    companion object {
        private val EXPO = UUID.fromString("00000000-0000-0000-0000-000000000037")
        private val capacity = AtomicInteger(5)
        private val userStatus = AtomicInteger(201)
        private val responseIds = AtomicInteger(0)
        private val responseBody =
            java.util.concurrent.atomic
                .AtomicReference<String?>()
        private val countStatus = AtomicInteger(204)
        private val detailsStatus = AtomicInteger(200)
        private val userOccupations = java.util.concurrent.ConcurrentHashMap<Long, String>()
        private val targetCapacities =
            java.util.concurrent.atomic
                .AtomicReference<Pair<Int, Int>?>()
        private val managedParticipants = CopyOnWriteArrayList<Long>()
        private val expoDayOffset = AtomicInteger(0)
        private val formDayOffset = AtomicInteger(0)

        // #209 교원·예비교사 외 유형 오픈 시각. null이면 폼 응답에 싣지 않는다.
        private val occupationOpenAt =
            java.util.concurrent.atomic
                .AtomicReference<String?>()

        @Volatile private var phoneVerificationRequired = false

        @Volatile private var verifiedSessionPhone = "01012345678"
        private val sessionStatus = AtomicInteger(200)
        private val sessionRequests = CopyOnWriteArrayList<String>()
        private val countGate =
            java.util.concurrent.atomic
                .AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()

        @Volatile private var upstreamAvailable = true
        private val userRequests = CopyOnWriteArrayList<JsonNode>()
        private val attendanceRequests = CopyOnWriteArrayList<String>()
        private val guardRequests = CopyOnWriteArrayList<String>()
        private val guardCommitSessions = CopyOnWriteArrayList<Long>()
        private val counts = CopyOnWriteArrayList<Long>()
        private val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val userGate =
            java.util.concurrent.atomic
                .AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()
        private val json =
            tools.jackson.module.kotlin
                .jacksonObjectMapper()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/") { exchange ->
                    val path = exchange.requestURI.path
                    assertEquals("supplier-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    val (status, body) =
                        when {
                            path == "/internal/participant-sessions/resolve" -> {
                                val request = json.readTree(exchange.requestBody.bufferedReader().use { it.readText() })
                                assertEquals(EXPO.toString(), request.path("expoId").stringValue())
                                sessionRequests.add(request.path("sessionId").stringValue())
                                sessionStatus.get() to
                                    if (sessionStatus.get() == 200) {
                                        """{"expoId":"$EXPO","phoneNumber":"$verifiedSessionPhone","expiresAt":"2100-01-01T00:00:00"}"""
                                    } else {
                                        """{"code":"SESSION_EXPIRED"}"""
                                    }
                            }

                            path == "/internal/standard-participants/admin-cancellation-status" -> {
                                val id = json.readTree(exchange.requestBody.bufferedReader().readText()).path("participantId").asLong()
                                200 to """{"participantId":$id,"entered":false,"traineeLinked":false,"linkedTraineeId":null}"""
                            }

                            path.contains("cancellation-guard/") -> {
                                guardRequests.add(exchange.requestMethod + ":" + path.split("/")[5] + ":" + path.split("/")[6])
                                if (exchange.requestMethod == "POST") {
                                    guardCommitSessions.add(
                                        json.readTree(exchange.requestBody.bufferedReader().readText()).path("sessionId").asLong(),
                                    )
                                }
                                204 to ""
                            }

                            path.endsWith("/preregister-session") -> {
                                attendanceRequests.add(exchange.requestMethod + ":" + path.split("/")[5])
                                204 to ""
                            }

                            path.contains("/standard-registrations/") -> {
                                counts.add(path.substringAfterLast('/').toLong())
                                countGate.getAndSet(null)?.let { (entered, release) ->
                                    entered.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                }
                                countStatus.get() to ""
                            }

                            path == "/internal/standard-participants/managed" -> {
                                val participants =
                                    managedParticipants.mapIndexed { index, id ->
                                        mapOf(
                                            "participantId" to id,
                                            "name" to "person",
                                            "occupation" to null,
                                            "representative" to (index == 0),
                                        )
                                    }
                                200 to
                                    json.writeValueAsString(
                                        mapOf("registered" to participants.isNotEmpty(), "participants" to participants),
                                    )
                            }

                            path == "/internal/standard-participants/details" -> {
                                val ids =
                                    json
                                        .readTree(
                                            exchange.requestBody.bufferedReader().readText(),
                                        ).path("participantIds")
                                        .toList()
                                        .map { it.asLong() }
                                detailsStatus.get() to
                                    json.writeValueAsString(ids.map { mapOf("participantId" to it, "occupation" to userOccupations[it]) })
                            }

                            path == "/internal/standard-participants" -> {
                                val request = json.readTree(exchange.requestBody.bufferedReader().readText())
                                userRequests.add(request)
                                val count =
                                    responseIds.get().takeIf { it > 0 }
                                        ?: (1 + json.readTree(request.get("informationJson").stringValue()).get("companions").size())
                                val ids = (42L until 42L + count).joinToString(",")
                                val response =
                                    responseBody.get()
                                        ?: (
                                            """{"participantId":42,"phoneNumber":"01012345678","participantIds":[$ids],""" +
                                                """"groupParticipantIds":[$ids]}"""
                                        )
                                if (userStatus.get() == 0 ||
                                    userStatus.get() in 200..299
                                ) {
                                    stored.putIfAbsent(request.get("requestId").stringValue(), response)
                                }
                                userGate.getAndSet(null)?.let { (entered, release) ->
                                    entered.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                }
                                userStatus.get() to (stored[request.get("requestId").stringValue()] ?: "")
                            }

                            !upstreamAvailable -> {
                                503 to ""
                            }

                            path.contains("/preregister-sessions/") -> {
                                val id = path.substringAfterLast('/').toLong()
                                200 to
                                    """{"id":$id,"expoId":"$EXPO","title":"Morning","startedAt":"2030-01-01T00:00:00Z","endedAt":"2030-01-01T01:00:00Z","place":"Place","capacity":${capacity.get()},"waitingCapacity":99,"closed":false,"revision":1,"cancelEndedAt":"2030-01-01T00:00:00Z"}"""
                                        .let { text ->
                                            targetCapacities.get()?.let { (t, n) ->
                                                text.dropLast(1) + ""","teacherCapacity":$t,"nonTeacherCapacity":$n}"""
                                            } ?: text
                                        }
                            }

                            path.startsWith("/internal/forms/") -> {
                                val now = OffsetDateTime.now().plusDays(formDayOffset.get().toLong())
                                // Target sessions (#173) need the representative occupation question.
                                val occupation =
                                    if (targetCapacities.get() == null) {
                                        ""
                                    } else {
                                        """,{"id":2,"title":"occupation","formType":"SELECT","jsonData":{},"otherJson":null,""" +
                                            """"dynamicFormType":"OCCUPATION"},{"id":3,"title":"school","formType":"TEXT",""" +
                                            """"jsonData":{},"otherJson":null,"dynamicFormType":"SCHOOL"}"""
                                    }
                                val open = occupationOpenAt.get()?.let { ""","occupationOpenAt":"$it"""" } ?: ""
                                200 to
                                    """{"id":"$EXPO","startDate":"${now.minusDays(
                                        1,
                                    )}","endDate":"${now.plusDays(
                                        1,
                                    )}","dynamicForm":[{"id":1,"title":"companions","formType":"COMPANION","jsonData":{},"otherJson":{"maxSelection":4},"dynamicFormType":"DEFAULT"}$occupation]$open}"""
                            }

                            else -> {
                                val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(expoDayOffset.get().toLong())
                                200 to
                                    """{"startedDay":"$today","finishedDay":"$today","participantPhoneVerificationRequired":$phoneVerificationRequired,"participantPhoneVerificationRevision":0}"""
                            }
                        }
                    if (status != 0) {
                        val bytes = body.toByteArray()
                        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
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
