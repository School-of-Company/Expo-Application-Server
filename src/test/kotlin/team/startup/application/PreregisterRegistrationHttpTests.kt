package team.startup.application

import com.sun.net.httpserver.HttpServer
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
        expoDayOffset.set(0)
        formDayOffset.set(0)
    }

    private fun submit(
        key: String? = "request",
        people: Int = 1,
        phone: String = "01012345678",
        session: Long = 1,
    ): Int {
        val companions = (2..people).map { mapOf("name" to "person$it", "occupation" to "GENERAL", "region" to "OTHER") }
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to "person",
                    "phoneNumber" to phone,
                    "personalInformationStatus" to true,
                    "informationJson" to mapper.writeValueAsString(mapOf("companions" to companions)),
                    "sessionId" to session,
                ),
            )
        val request = post("/application/pre-standard/$EXPO").contentType(MediaType.APPLICATION_JSON).content(body)
        if (key != null) request.header("Idempotency-Key", key)
        return mvc
            .perform(request)
            .andReturn()
            .response.status
    }

    private fun capacity(): JsonNode {
        val response = mvc.perform(get("/application/expos/$EXPO/preregister-sessions/1/capacity")).andReturn().response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        return mapper.readTree(response.contentAsString)
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

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
        assertEquals(listOf(42L, 43L, 44L), mapper.readTree(status.contentAsString).get("participantIds").toList().map { it.asLong() })
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
        assertEquals(409, submit("other", phone = "01012345678"))
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
    fun `busy or failed count delivery does not starve another Expo`() {
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
            reservations.deliverCounts { _, participantId ->
                delivered.add(participantId)
                if (participantId ==
                    42L
                ) {
                    throw org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    )
                }
            }
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
        assertFalse(available.has("waitingRemaining"))
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
        assertEquals(409, submit(people = 3))
        assertTrue(userRequests.isEmpty())
        assertEquals(0, count("tb_preregister_reservation"))
        assertEquals(0, count("tb_preregister_application"))
    }

    @Test
    fun `lost User response keeps seats and recovers original snapshot and requestId without Expo or Form`() {
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
        assertEquals(409, submit(people = 1))
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
        assertEquals(409, submit())
        assertEquals(1, capacity().get("remaining").intValue())
        assertEquals(409, submit())
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
        assertEquals(200, cancel("test-token", 42))
        assertEquals(201, submit("again", people = 2, session = 2))
        attendance.dispatch()
        assertEquals(listOf("PUT:42", "PUT:43", "DELETE:42", "DELETE:43", "PUT:42", "PUT:43"), attendanceRequests.toList())
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
        private val expoDayOffset = AtomicInteger(0)
        private val formDayOffset = AtomicInteger(0)
        private val countGate =
            java.util.concurrent.atomic
                .AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()

        @Volatile private var upstreamAvailable = true
        private val userRequests = CopyOnWriteArrayList<JsonNode>()
        private val attendanceRequests = CopyOnWriteArrayList<String>()
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

                            path == "/internal/standard-participants" -> {
                                val request = json.readTree(exchange.requestBody.bufferedReader().readText())
                                userRequests.add(request)
                                val count =
                                    responseIds.get().takeIf { it > 0 }
                                        ?: (1 + json.readTree(request.get("informationJson").stringValue()).get("companions").size())
                                val ids = (42L until 42L + count).joinToString(",")
                                val response =
                                    responseBody.get()
                                        ?: """{"participantId":42,"phoneNumber":"01012345678","participantIds":[$ids],"groupParticipantIds":[$ids]}"""
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
                                    """{"id":$id,"expoId":"$EXPO","title":"Morning","startedAt":"2030-01-01T00:00:00Z","endedAt":"2030-01-01T01:00:00Z","place":"Place","capacity":${capacity.get()},"waitingCapacity":99,"closed":false,"revision":1}"""
                            }

                            path.startsWith("/internal/forms/") -> {
                                val now = OffsetDateTime.now().plusDays(formDayOffset.get().toLong())
                                200 to
                                    """{"id":"$EXPO","startDate":"${now.minusDays(
                                        1,
                                    )}","endDate":"${now.plusDays(
                                        1,
                                    )}","dynamicForm":[{"id":1,"title":"companions","formType":"COMPANION","jsonData":{},"otherJson":{"maxSelection":4},"dynamicFormType":"DEFAULT"}]}"""
                            }

                            else -> {
                                val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(expoDayOffset.get().toLong())
                                200 to """{"startedDay":"$today","finishedDay":"$today"}"""
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
