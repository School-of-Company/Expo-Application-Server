package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false", "application.registration.enabled=true", "application.attendance.enabled=true",
        "application.registration.waitlist-intake-enabled=true", "application.registration.recovery-delay-ms=3600000",
        "application.registration.waitlist-promotion-delay-ms=3600000", "application.attendance.poll-delay-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PreregisterCompanionHttpTests {
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
                "tb_preregister_session_state, tb_preregister_deleted_expo, tb_standard_program_application RESTART IDENTITY CASCADE",
        )
        parties.clear()
        details.clear()
        released.clear()
        reportSchool = false
        smsTries.clear()
        userRequests.clear()
        nextId.set(42)
        capacity.set(3)
        waitingCapacity.set(5)
        maxSelection.set(4)
        closed = false
        startedAt.set(Instant.parse("2030-01-01T00:00:00Z"))
        userReject.set(0)
        target.set(null)
        occupationOpenAt.set(null)
    }

    @Test
    fun `target sessions add companions only into their own category seats and never wait`() {
        target.set(1 to 2)
        assertEquals(201, register(PHONE, "first").status)
        val applicationId = representativeItem()["applicationId"].asString()
        // The non-teacher seats are full; the free teacher seat is not lent and no waiting addition is made.
        val rejected = add(applicationId, companion("second"))
        assertEquals(409, rejected.status, rejected.contentAsString)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_reservation WHERE state = 'WAITING'", Int::class.java))
        val response = add(applicationId, companion("teacher", "TEACHER"))
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(
            listOf("42:OTHER", "43:OTHER", "44:TEACHER"),
            jdbc.query("SELECT participant_id, seat_category FROM tb_preregister_application ORDER BY id") { rs, _ ->
                "${rs.getLong(1)}:${rs.getString(2)}"
            },
        )
    }

    @Test
    fun `before occupationOpenAt only teachers are added as companions`() {
        assertEquals(201, register(PHONE, "first").status)
        val applicationId = representativeItem()["applicationId"].asString()
        occupationOpenAt.set(OffsetDateTime.now().plusHours(1).toString())
        val student = add(applicationId, companion("kid", "ELEMENTARY_STUDENT"))
        assertEquals(400, student.status)
        assertEquals("OCCUPATION_NOT_OPEN", mapper.readTree(student.contentAsString)["code"].asString())
        assertEquals(201, add(applicationId, companion("teacher", "TEACHER")).status)
    }

    @Test
    fun `representative adds a companion within free seats and the new companion is confirmed and texted to the representative`() {
        assertEquals(201, register(PHONE, "first").status)
        val representative = representativeItem()
        assertTrue(representative["companionAddable"].asBoolean(), representative.toString())
        // 좌석은 1석이지만 대기 정원까지 셈해 일행 상한(5명) 안에서 3명을 더 받을 수 있다.
        assertEquals(3, representative["remainingCompanionSlots"].asInt())
        assertEquals("", representative["companionAddBlockedReason"].asString())
        val companionItem = items().single { it["participantId"].asLong() == 43L }
        assertFalse(companionItem["companionAddable"].asBoolean())
        assertEquals("NOT_REPRESENTATIVE_PREREGISTRATION", companionItem["companionAddBlockedReason"].asString())

        val response = add(representative["applicationId"].asString(), companion("second"))
        assertEquals(201, response.status, response.contentAsString)
        val body = mapper.readTree(response.contentAsString)
        assertEquals(mapper.readTree("[44]"), body["participantIds"])
        assertEquals(representative["applicationId"].asString(), body["applicationId"].asString())

        // User는 같은 대표자 정보로 새 동행자만 받고, 등록 완료 이벤트로 새 동행자 QR을 대표자 번호에 보낸다.
        val resent = userRequests.last()
        assertEquals(PHONE, resent["phoneNumber"].asString())
        val sentCompanions = mapper.readTree(resent["informationJson"].asString())["companions"]
        assertEquals(listOf("second"), (0 until sentCompanions.size()).map { sentCompanions[it]["name"].asString() })
        assertFalse(resent["requestId"].asString() == userRequests.first()["requestId"].asString())
        assertEquals(
            listOf("42:42:CONFIRMED", "42:43:CONFIRMED", "42:44:CONFIRMED"),
            jdbc.query("SELECT representative_id, participant_id, status FROM tb_preregister_application ORDER BY id") { rs, _ ->
                "${rs.getLong(1)}:${rs.getLong(2)}:${rs.getString(3)}"
            },
        )
        assertEquals(
            "USER:42",
            jdbc.queryForObject(
                "SELECT actor_type || ':' || actor_participant_id FROM tb_preregister_transition WHERE participant_id = 44",
                String::class.java,
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_session_attendance_outbox WHERE participant_id = 44", Int::class.java))
        // 좌석이 다 찼어도 대기 정원이 남아 있으면 대기로 더 받을 수 있다.
        val full = representativeItem()
        assertTrue(full["companionAddable"].asBoolean())
        assertEquals(2, full["remainingCompanionSlots"].asInt())
        assertEquals("", full["companionAddBlockedReason"].asString())
    }

    @Test
    fun `missing seats queue the addition and promotion attaches it to the confirmed representative`() {
        capacity.set(2)
        assertEquals(201, register(PHONE, "first").status)
        val id = representativeItem()["applicationId"].asString()
        val sent = userRequests.size
        val waiting = add(id, companion("second"), key = "wait-1")
        assertEquals(202, waiting.status, waiting.contentAsString)
        val body = mapper.readTree(waiting.contentAsString)
        assertEquals("WAITING", body["status"].asString())
        assertEquals(1, body["position"].asInt())
        assertEquals(0, body["participantIds"].size())
        assertEquals(sent, userRequests.size)
        // 같은 키 재시도는 새 대기를 만들지 않고, 대기 중에는 다른 추가를 받지 않는다.
        assertEquals(202, add(id, companion("second"), key = "wait-1").status)
        assertEquals("ADDITION_IN_PROGRESS", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(409, add(id, companion("third")).status)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_reservation WHERE state = 'WAITING'", Int::class.java))

        // 자리가 나면 일반 대기 일행과 같은 승급 경로로 RESERVED가 되고, 복구가 대표자에게 붙여 확정한다.
        capacity.set(3)
        storedDefinition("capacity", "3")
        recovery.promote()
        waitlist.flush()
        recovery.recover()
        assertEquals(sent + 1, userRequests.size)
        assertEquals(PHONE, userRequests.last()["phoneNumber"].asString())
        assertEquals(
            listOf("42:42:CONFIRMED", "42:43:CONFIRMED", "42:44:CONFIRMED"),
            jdbc.query("SELECT representative_id, participant_id, status FROM tb_preregister_application ORDER BY id") { rs, _ ->
                "${rs.getLong(1)}:${rs.getLong(2)}:${rs.getString(3)}"
            },
        )
        assertEquals(201, add(id, companion("second"), key = "wait-1").status)
    }

    @Test
    fun `a waiting addition is withdrawn by the owner and promotion of a stale one never saves to User`() {
        capacity.set(2)
        assertEquals(201, register(PHONE, "first").status)
        val id = representativeItem()["applicationId"].asString()
        assertEquals(202, add(id, companion("second")).status)
        val withdrawn =
            mvc
                .perform(
                    post("/application/expos/$EXPO/my-applications/preregister-waitlist/cancellation")
                        .with(csrf())
                        .cookie(Cookie("PARTICIPANT_SESSION", "owner")),
                ).andReturn()
                .response
        assertEquals(204, withdrawn.status, withdrawn.contentAsString)
        assertEquals("", representativeItem()["companionAddBlockedReason"].asString())

        // 대표자 신청이 그사이 취소되면 승급된 추가는 User에 저장하지 않고 좌석을 돌려준다.
        assertEquals(202, add(id, companion("third")).status)
        val sent = userRequests.size
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 42")
        capacity.set(3)
        storedDefinition("capacity", "3")
        recovery.promote()
        waitlist.flush()
        recovery.recover()
        assertEquals(sent, userRequests.size)
        assertEquals(
            "REJECTED",
            jdbc.queryForObject(
                "SELECT state FROM tb_preregister_reservation WHERE companion_representative_id = 42 AND withdrawn_at IS NULL",
                String::class.java,
            ),
        )
    }

    @Test
    fun `without waiting intake missing seats still reject the whole addition`() {
        capacity.set(2)
        waitingCapacity.set(0)
        assertEquals(201, register(PHONE, "first").status)
        val id = representativeItem()["applicationId"].asString()
        assertEquals("SEATS_UNAVAILABLE", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(409, add(id, companion("second")).status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_reservation WHERE state = 'WAITING'", Int::class.java))
    }

    @Test
    fun `form companion limit and session state block additions`() {
        maxSelection.set(1)
        capacity.set(5)
        assertEquals(201, register(PHONE, "first").status)
        val id = representativeItem()["applicationId"].asString()
        assertEquals("COMPANION_LIMIT_REACHED", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(409, add(id, companion("second")).status)
        maxSelection.set(2)
        val overLimit = add(id, companion("second"), companion("third"))
        assertEquals("COMPANION_LIMIT_REACHED", mapper.readTree(overLimit.contentAsString)["code"].asString())
        maxSelection.set(4)
        assertEquals(409, add(id, companion("first")).status)
        closed = true
        storedDefinition("closed", "true")
        assertEquals("SESSION_CLOSED", representativeItem()["companionAddBlockedReason"].asString())
        val closedAddition = add(id, companion("second"))
        assertEquals(mapper.readTree("""{"status":409,"code":"SESSION_CLOSED"}"""), mapper.readTree(closedAddition.contentAsString))
        closed = false
        storedDefinition("closed", "false")
        startedAt.set(Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        storedDefinition("startedAt", "\"${startedAt.get()}\"")
        assertEquals("SESSION_STARTED", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(409, add(id, companion("second")).status)
        startedAt.set(Instant.parse("2030-01-01T00:00:00Z"))
        storedDefinition("startedAt", "\"${startedAt.get()}\"")
        val companionRow = items().single { it["participantId"].asLong() == 43L }["applicationId"].asString()
        assertEquals(404, add(companionRow, companion("second")).status)
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 42")
        assertEquals(409, add(id, companion("second")).status)
    }

    @Test
    fun `another owner's application, the five person party limit and a waiting queue block additions`() {
        capacity.set(10)
        assertEquals(201, register(PHONE, "first").status)
        assertEquals(201, register(OTHER_PHONE, null, session = "other-owner").status)
        val id = representativeItem()["applicationId"].asString()
        val others = representativeItem("other-owner")["applicationId"].asString()
        assertEquals(404, add(others, companion("intruder")).status)
        assertEquals(201, add(id, companion("second"), companion("third"), companion("fourth")).status)
        assertEquals("COMPANION_LIMIT_REACHED", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(0, representativeItem()["remainingCompanionSlots"].asInt())
        // #271: 6번째 인원은 조회의 차단 사유와 같은 code로 거절한다.
        val sixth = add(id, companion("fifth"))
        assertEquals(mapper.readTree("""{"status":409,"code":"COMPANION_LIMIT_REACHED"}"""), mapper.readTree(sixth.contentAsString))

        capacity.set(1)
        storedDefinition("capacity", "1")
        assertEquals(202, register(THIRD_PHONE, null, session = "third-owner").status)
        capacity.set(10)
        storedDefinition("capacity", "10")
        // 대기 중인 일행이 있으면 좌석이 비어도 앞지르지 않고 그 뒤에 선다.
        val other = representativeItem("other-owner")
        assertEquals("", other["companionAddBlockedReason"].asString())
        val late = add(others, companion("late"), session = "other-owner")
        assertEquals(202, late.status, late.contentAsString)
        assertEquals(2, mapper.readTree(late.contentAsString)["position"].asInt())
    }

    @Test
    fun `concurrent additions from two parties never exceed the session capacity and the loser waits`() {
        capacity.set(3)
        assertEquals(201, register(PHONE, null).status)
        assertEquals(201, register(OTHER_PHONE, null, session = "other-owner").status)
        val first = representativeItem()["applicationId"].asString()
        val second = representativeItem("other-owner")["applicationId"].asString()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(Triple(first, "owner", "late-a"), Triple(second, "other-owner", "late-b")).map { (id, session, name) ->
                    pool.submit<Int> {
                        start.await(10, TimeUnit.SECONDS)
                        add(id, companion(name), session = session).status
                    }
                }
            start.countDown()
            assertEquals(listOf(201, 202), results.map { it.get(30, TimeUnit.SECONDS) }.sorted())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_application WHERE status = 'CONFIRMED'", Int::class.java))
    }

    @Test
    fun `adding after a companion is cancelled sends the cancelled companion id to User and never the representative`() {
        capacity.set(4)
        assertEquals(201, register(PHONE, "first").status)
        // 첫 등록과 취소가 없는 추가에는 필드가 없어 기존 요청과 같다.
        assertFalse(userRequests.first().has("releasedParticipantIds"))
        val id = representativeItem()["applicationId"].asString()

        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 43")
        assertEquals(201, add(id, companion("third"), key = "after-cancel").status)
        assertEquals(mapper.readTree("[43]"), userRequests.last()["releasedParticipantIds"])
        // 같은 키 재시도는 User를 다시 부르지 않는다.
        val sent = userRequests.size
        assertEquals(201, add(id, companion("third"), key = "after-cancel").status)
        assertEquals(sent, userRequests.size)
    }

    @Test
    fun `without a cancelled companion the User request has no releasedParticipantIds field`() {
        capacity.set(4)
        assertEquals(201, register(PHONE, "first").status)
        val id = representativeItem()["applicationId"].asString()
        assertEquals(201, add(id, companion("second")).status)
        assertTrue(userRequests.none { it.has("releasedParticipantIds") })
    }

    @Test
    fun `a waiting addition takes the cancelled companions as of its first delivery and a waiting one is not released`() {
        capacity.set(2)
        assertEquals(201, register(PHONE, "first").status)
        val id = representativeItem()["applicationId"].asString()
        assertEquals(202, add(id, companion("second"), key = "wait").status)
        // 대기 중인 추가는 취소가 아니라 보내지 않고, 대기 중에 생긴 취소는 승급 뒤 첫 전달에 반영된다.
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 43")
        capacity.set(3)
        storedDefinition("capacity", "3")
        recovery.promote()
        waitlist.flush()
        recovery.recover()
        assertEquals(mapper.readTree("[43]"), userRequests.last()["releasedParticipantIds"])
        assertEquals(
            "[43]",
            jdbc.queryForObject(
                "SELECT user_request::jsonb -> 'releasedParticipantIds' FROM tb_preregister_reservation WHERE request_id = 'wait'",
                String::class.java,
            ),
        )
    }

    @Test
    fun `representative reapplies after the whole party is cancelled with any companion composition`() {
        // #196: User 재신청 응답의 일행에는 취소된 동행자가 남는다. 이 표가 이슈의 재현 표다.
        data class Case(
            val previous: List<String>,
            val next: List<String>,
            val status: Int,
            val confirmed: List<Long>,
            // 원장에 신청 행이 없는 User 동행자(이전 동행자 중 마지막)
            val orphan: Boolean = false,
        )
        val cases =
            listOf(
                Case(emptyList(), emptyList(), 201, listOf(42L)),
                Case(listOf("b"), listOf("b"), 201, listOf(42L, 43L)),
                Case(listOf("b"), emptyList(), 201, listOf(42L)),
                Case(listOf("b"), listOf("c"), 201, listOf(42L, 44L)),
                // User 일행이 5명을 넘어도 취소된 동행자를 빼면 맞는다.
                Case(listOf("b", "c", "d", "e"), listOf("f"), 201, listOf(42L, 47L)),
                // 취소된 동행자 중 일부만 다시 신청하면 어느 ID가 이번 일행인지 알 수 없다. 좌석을 잡고 검토로 남긴다.
                Case(listOf("b", "c"), listOf("b"), 502, emptyList()),
                // 취소되지 않은 ID가 남으면 인원이 맞아도 다른 사람을 확정하지 않는다.
                Case(listOf("b", "x"), listOf("b"), 502, emptyList(), orphan = true),
            )
        cases.forEach { (previous, next, status, confirmed, orphan) ->
            reset()
            capacity.set(5)
            assertEquals(201, register(PHONE, null, companions = previous).status, "$previous")
            jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED'")
            // #237: 대표자에게 프로그램 신청이 남아 있으면 대표자를 바꾸지 않는 재등록이다.
            jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (42, 1)")
            if (orphan) jdbc.update("DELETE FROM tb_preregister_application WHERE participant_id = ?", 42L + previous.size)
            val response = register(PHONE, null, companions = next)
            assertEquals(status, response.status, "$previous -> $next: ${response.contentAsString}")
            assertFalse(userRequests.last().has("replaceRepresentative"))
            assertEquals(
                confirmed,
                jdbc.queryForList(
                    "SELECT participant_id FROM tb_preregister_application WHERE status = 'CONFIRMED' ORDER BY participant_id",
                    Long::class.java,
                ),
                "$previous -> $next",
            )
            if (status == 502) {
                assertEquals(
                    502,
                    jdbc.queryForObject(
                        "SELECT error_status FROM tb_preregister_reservation WHERE state = 'RESERVED'",
                        Int::class.java,
                    ),
                )
            }
        }
    }

    @Test
    fun `a new party after the whole party is cancelled replaces the representative with the submitted one`() {
        // #237 dev 재현: 조화정(초등학생)+동행자 3명 신청, 전원 취소 뒤 같은 번호로 홍(예비교사)이 신청하면 조화정이 다시 확정됐다.
        target.set(1 to 4)
        capacity.set(5)
        val first = register(PHONE, null, companions = listOf("b", "c", "d"), name = "조화정", occupation = "ELEMENTARY_STUDENT")
        assertEquals(201, first.status, first.contentAsString)
        assertFalse(userRequests.first().has("replaceRepresentative"))
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED'")

        val response = register(PHONE, null, name = "홍", occupation = "PRE_SERVICE_TEACHER", school = "AI교육원")
        assertEquals(201, response.status, response.contentAsString)
        val sent = userRequests.last()
        assertTrue(sent["replaceRepresentative"].asBoolean(), sent.toString())
        assertEquals(mapper.readTree("[43,44,45]"), sent["releasedParticipantIds"])
        assertEquals(listOf(42L to "홍"), parties.getValue(PHONE))
        // 확정 대상·좌석 구분·답변 스냅샷이 모두 교체된 대표자 기준이다.
        assertEquals(
            listOf("42:42:TEACHER:홍:PRE_SERVICE_TEACHER"),
            jdbc.query(
                "SELECT a.representative_id, a.participant_id, a.seat_category, r.user_request::jsonb ->> 'name', " +
                    "r.user_request::jsonb ->> 'occupation' FROM tb_preregister_application a JOIN tb_preregister_reservation r " +
                    "ON r.expo_id = a.expo_id AND r.request_id = a.reservation_request_id WHERE a.status = 'CONFIRMED'",
            ) { rs, _ -> (1..5).joinToString(":") { rs.getString(it) } },
        )
        // 교체한 신청에 동행자를 더해도 대표자를 다시 바꾸지 않는다.
        val confirmed = items().single { it["type"].asString() == "PREREGISTER" && it["status"].asString() == "CONFIRMED" }
        assertEquals(201, add(confirmed["applicationId"].asString(), companion("e")).status)
        assertFalse(userRequests.last().has("replaceRepresentative"))
    }

    @Test
    fun `User rejection releases held seats and an idempotent retry does not save twice`() {
        capacity.set(4)
        assertEquals(201, register(PHONE, null).status)
        val id = representativeItem()["applicationId"].asString()
        userReject.set(409)
        assertEquals(409, add(id, companion("second"), key = "rejected").status)
        assertEquals(
            "REJECTED",
            jdbc.queryForObject("SELECT state FROM tb_preregister_reservation WHERE request_id = 'rejected'", String::class.java),
        )
        assertEquals(4, representativeItem()["remainingCompanionSlots"].asInt())
        userReject.set(0)
        val created = add(id, companion("second"), key = "added")
        assertEquals(201, created.status)
        val sent = userRequests.size
        val replay = add(id, companion("second"), key = "added")
        assertEquals(201, replay.status)
        assertEquals(mapper.readTree(created.contentAsString), mapper.readTree(replay.contentAsString))
        assertEquals(sent, userRequests.size)
        assertEquals(409, add(id, companion("other"), key = "added").status)
    }

    @Test
    fun `a same-name teacher from another school is a new companion once User reports the school`() {
        assertEquals(201, register(PHONE, null, teachers = listOf("teacher")).status)
        val id = representativeItem()["applicationId"].asString()
        val otherSchool = companion("teacher", "TEACHER") + mapOf("school" to "other")
        // 학교를 싣지 않는 옛 User 응답이면 이름·구분만 본다.
        assertEquals(409, add(id, otherSchool).status)
        reportSchool = true
        assertEquals(409, add(id, companion("teacher", "TEACHER")).status)
        // #225: User처럼 이름·구분·학교가 모두 같아야 같은 사람이다.
        val response = add(id, otherSchool)
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(mapper.readTree("[44]"), mapper.readTree(response.contentAsString)["participantIds"])
    }

    @Test
    fun `a companion named like the representative is not a known companion`() {
        assertEquals(201, register(PHONE, null).status)
        // #225: User는 대표자를 빼고 동행자끼리만 같은 사람을 가린다.
        val response = add(representativeItem()["applicationId"].asString(), companion("person"))
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(mapper.readTree("[43]"), mapper.readTree(response.contentAsString)["participantIds"])
    }

    @Test
    fun `addition needs the participant session csrf token and valid companions`() {
        assertEquals(201, register(PHONE, null).status)
        val id = representativeItem()["applicationId"].asString()
        val path = "/application/expos/$EXPO/my-applications/PREREGISTER/$id/companions"
        val body = mapper.writeValueAsString(mapOf("companions" to listOf(companion("second"))))
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
        assertEquals(400, add(id).status)
        assertEquals(400, add(id, mapOf("name" to "x", "occupation" to "PILOT", "region" to "OTHER")).status)
        assertEquals(400, add(id, companion("dup"), companion("dup")).status)
    }

    @Test
    fun `a companion who cancelled everything frees a party slot while their history and id stay`() {
        // #262 dev 재현: 대표자+동행자 4명 중 한 명(여기서는 46)이 모든 신청을 취소하면 현재 일행은 4명이다.
        fullPartyWithCancelled()
        val representative = representativeItem()
        assertTrue(representative["companionAddable"].asBoolean(), representative.toString())
        assertEquals(1, representative["remainingCompanionSlots"].asInt())

        val response = add(representative["applicationId"].asString(), companion("e"))
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(mapper.readTree("[46]"), userRequests.last()["releasedParticipantIds"])
        assertEquals(listOf(42L, 43L, 44L, 45L, 47L), confirmedParticipants())
        assertEquals("COMPANION_LIMIT_REACHED", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(409, add(representative["applicationId"].asString(), companion("f")).status)
        // 취소 이력과 참가자 ID는 지우지 않는다.
        assertEquals(
            "CANCELLED",
            jdbc.queryForObject("SELECT status FROM tb_preregister_application WHERE participant_id = 46", String::class.java),
        )
        // 해제된 옛 동행자는 다시 회차를 추가할 수 없다.
        assertEquals(400, addSession(46).status)
        assertEquals(listOf(42L, 43L, 44L, 45L, 47L), confirmedParticipants())
    }

    @Test
    fun `a companion with an application left still counts toward the party limit`() {
        fullPartyWithCancelled()
        // 사전등록은 모두 취소했어도 프로그램 신청이 남아 있으면 현재 인원이다.
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (46, 1)")
        val representative = representativeItem()
        assertEquals("COMPANION_LIMIT_REACHED", representative["companionAddBlockedReason"].asString())
        assertEquals(0, representative["remainingCompanionSlots"].asInt())
        val sent = userRequests.size
        assertEquals(409, add(representative["applicationId"].asString(), companion("e")).status)
        assertEquals(sent, userRequests.size)
    }

    @Test
    fun `a returning cancelled companion takes the free slot and blocks a new companion`() {
        fullPartyWithCancelled()
        val id = representativeItem()["applicationId"].asString()
        val back = addSession(46)
        assertEquals(201, back.status, back.contentAsString)
        assertEquals(listOf(42L, 43L, 44L, 45L, 46L), confirmedParticipants())
        assertEquals("COMPANION_LIMIT_REACHED", representativeItem()["companionAddBlockedReason"].asString())
        assertEquals(409, add(id, companion("e")).status)
    }

    @Test
    fun `a cancelled companion cannot return while a companion addition is waiting`() {
        fullPartyWithCancelled()
        capacity.set(4)
        storedDefinition("capacity", "4")
        val id = representativeItem()["applicationId"].asString()
        assertEquals(202, add(id, companion("e")).status)
        assertEquals(409, addSession(46).status)
        assertEquals(listOf(42L, 43L, 44L, 45L), confirmedParticipants())
    }

    @Test
    fun `concurrent addition and return of a cancelled companion keep the party at five`() {
        listOf<(String) -> Int>(
            { id -> add(id, companion("e")).status },
            { id -> add(id, companion("f")).status },
            { _ -> addSession(46).status },
        ).let { operations -> listOf(operations[0] to operations[1], operations[0] to operations[2]) }
            .forEach { (first, second) ->
                reset()
                fullPartyWithCancelled()
                val id = representativeItem()["applicationId"].asString()
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val results =
                        listOf(first, second).map { operation ->
                            pool.submit<Int> {
                                start.await(10, TimeUnit.SECONDS)
                                operation(id)
                            }
                        }
                    start.countDown()
                    val statuses = results.map { it.get(30, TimeUnit.SECONDS) }
                    assertEquals(1, statuses.count { it == 201 }, "$statuses")
                } finally {
                    pool.shutdownNow()
                }
                assertEquals(5, confirmedParticipants().size, "${confirmedParticipants()}")
                assertEquals(
                    0,
                    jdbc.queryForObject("SELECT count(*) FROM tb_preregister_reservation WHERE state = 'RESERVED'", Int::class.java),
                )
            }
    }

    // 대표자 42와 동행자 43~46을 등록하고 46의 사전등록을 모두 취소한다.
    private fun fullPartyWithCancelled() {
        capacity.set(10)
        assertEquals(201, register(PHONE, null, companions = listOf("a", "b", "c", "d")).status)
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 46")
    }

    private fun confirmedParticipants(): List<Long> =
        jdbc
            .queryForList(
                "SELECT DISTINCT participant_id FROM tb_preregister_application WHERE status = 'CONFIRMED' ORDER BY participant_id",
                Long::class.java,
            ).filterNotNull()

    private fun addSession(vararg participantIds: Long): MockHttpServletResponse =
        mvc
            .perform(
                post("/application/expos/$EXPO/my-applications/preregister-sessions")
                    .with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("sessionId" to 1, "participantIds" to participantIds.toList()))),
            ).andReturn()
            .response

    private fun register(
        phone: String,
        companion: String?,
        session: String = "owner",
        companions: List<String> = emptyList(),
        teachers: List<String> = emptyList(),
        name: String = "person",
        occupation: String = "GENERAL",
        school: String? = null,
    ): MockHttpServletResponse {
        sessions[session] = phone
        val companions = (listOfNotNull(companion) + companions).map { companion(it) } + teachers.map { companion(it, "TEACHER") }
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    "phoneNumber" to phone,
                    "personalInformationStatus" to true,
                    "informationJson" to
                        mapper.writeValueAsString(
                            // Target sessions (#173) need the representative occupation.
                            mapOf("companions" to companions, "occupation" to occupation) + listOfNotNull(school?.let { "school" to it }),
                        ),
                    "sessionId" to 1,
                ),
            )
        return mvc
            .perform(post("/application/pre-standard/$EXPO").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn()
            .response
    }

    private fun items(session: String = "owner"): List<JsonNode> {
        val response =
            mvc.perform(get("/application/expos/$EXPO/my-applications").cookie(Cookie("PARTICIPANT_SESSION", session))).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        return mapper.readTree(response.contentAsString)["applications"].toList()
    }

    private fun representativeItem(session: String = "owner"): JsonNode {
        val representative = parties.getValue(sessions.getValue(session)).first().first
        return items(session).single { it["type"].asString() == "PREREGISTER" && it["participantId"].asLong() == representative }
    }

    private fun add(
        applicationId: String,
        vararg companions: Map<String, String>,
        key: String? = null,
        session: String = "owner",
    ): MockHttpServletResponse {
        val request =
            post("/application/expos/$EXPO/my-applications/PREREGISTER/$applicationId/companions")
                .with(csrf())
                .cookie(Cookie("PARTICIPANT_SESSION", session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("companions" to companions.toList())))
        if (key != null) request.header("Idempotency-Key", key)
        return mvc.perform(request).andReturn().response
    }

    // Expo가 승인한 회차 변경을 저장 정의에 반영한다. 이 테스트는 변경 승인 절차가 아니라 추가 조건만 본다.
    private fun storedDefinition(
        field: String,
        value: String,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_session_state SET definition = jsonb_set(definition::jsonb, ?::text[], ?::jsonb)::text",
            "{$field}",
            value,
        )
    }

    private fun companion(
        name: String,
        occupation: String = "GENERAL",
    ) = mapOf("name" to name, "occupation" to occupation, "region" to "OTHER") +
        if (occupation == "TEACHER") mapOf("school" to "school") else emptyMap()

    companion object {
        private val EXPO = UUID.fromString("00000000-0000-0000-0000-000000000168")
        private const val PHONE = "01000000001"
        private const val OTHER_PHONE = "01000000002"
        private const val THIRD_PHONE = "01077778888"
        private val capacity = AtomicInteger(3)
        private val maxSelection = AtomicInteger(4)
        private val waitingCapacity = AtomicInteger(5)
        private val startedAt = AtomicReference(Instant.parse("2030-01-01T00:00:00Z"))
        private val userReject = AtomicInteger(0)
        private val nextId = AtomicLong(42)

        // #173 (teacher, nonTeacher) capacities; the capacity field must hold their sum.
        private val target = AtomicReference<Pair<Int, Int>?>()

        // #209 교원·예비교사 외 유형 오픈 시각. null이면 폼 응답에 싣지 않는다.
        private val occupationOpenAt = AtomicReference<String?>()

        @Volatile private var closed = false
        private val sessions = ConcurrentHashMap<String, String>()

        // 번호별 User 일행: (참가자 ID, 이름). 첫 항목이 대표자다.
        private val parties = ConcurrentHashMap<String, MutableList<Pair<Long, String>>>()

        // User가 해제(released)해 managed 목록에서 뺀 동행자 ID.
        private val released = ConcurrentHashMap.newKeySet<Long>()

        // 동행자 ID별 (구분, 학교). 없으면 GENERAL이고 학교가 없다.
        private val details = ConcurrentHashMap<Long, Pair<String, String?>>()

        // #225 User managed 응답에 school을 싣는지. 옛 User는 싣지 않는다.
        @Volatile private var reportSchool = false
        private val smsTries = ConcurrentHashMap<String, Int>()
        private val userRequests = CopyOnWriteArrayList<JsonNode>()
        private val json =
            tools.jackson.module.kotlin
                .jacksonObjectMapper()

        private fun definition(id: Long) =
            """{"id":$id,"expoId":"$EXPO","title":"Morning","startedAt":"${startedAt.get()}","endedAt":"2030-01-01T01:00:00Z",""" +
                """"place":"Place","capacity":${capacity.get()},"waitingCapacity":${waitingCapacity.get()},"closed":$closed,""" +
                """"revision":1,"cancelEndedAt":"2030-01-01T00:00:00Z"""" +
                (target.get()?.let { (t, n) -> ""","teacherCapacity":$t,"nonTeacherCapacity":$n""" } ?: "") + "}"

        private fun detail(node: JsonNode) = node["occupation"].asString() to node.path("school").asString().ifEmpty { null }

        // User의 재등록 규칙: 같은 번호면 새 동행자만 만들어 participantIds로, 일행 전체를 groupParticipantIds로 준다.
        @Synchronized
        private fun saveParticipants(request: JsonNode): Pair<Int, String> {
            userRequests.add(request)
            val phone = request["phoneNumber"].asString()
            val companions: JsonNode = json.readTree(request["informationJson"].asString()).path("companions")
            val people = companions.toList()
            // User 계약: releasedParticipantIds는 managed 목록에서 빠지고, 같은 사람을 다시 신청하면 되살아난다.
            request.path("releasedParticipantIds").forEach { released.add(it.asLong()) }
            parties[phone].orEmpty().filter { person -> people.any { it["name"].asString() == person.second } }.forEach {
                released.remove(it.first)
            }
            val party = parties[phone]
            if (party == null) {
                val created =
                    (listOf(request["name"].asString()) + people.map { it["name"].asString() }).map {
                        nextId.getAndIncrement() to
                            it
                    }
                created.drop(1).zip(people).forEach { (person, node) -> details[person.first] = detail(node) }
                parties[phone] = created.toMutableList()
                smsTries[phone] = 1
                val ids = created.joinToString(",") { it.first.toString() }
                return 201 to
                    """{"participantId":${created.first().first},"phoneNumber":"$phone","participantIds":[$ids],"groupParticipantIds":[$ids]}"""
            }
            if (userReject.get() != 0) return userReject.get() to "{}"
            if (request.path("replaceRepresentative").asBoolean(false)) {
                // #237 User 계약: 대표자를 이번 요청 값으로 바꾸고 기존 동행자를 모두 빼며, 같은 사람의 동행자는 되살린다.
                val previous = party.drop(1)
                val created = mutableListOf<Long>()
                val requested =
                    people.map { node ->
                        previous.firstOrNull { it.second == node["name"].asString() && details[it.first] == detail(node) }
                            ?: (nextId.getAndIncrement() to node["name"].asString()).also {
                                details[it.first] = detail(node)
                                created.add(it.first)
                            }
                    }
                val representative = party.first().first to request["name"].asString()
                party.clear()
                party.add(representative)
                party.addAll(requested)
                smsTries[phone] = 1
                val group = party.joinToString(",") { it.first.toString() }
                return 201 to
                    """{"participantId":${representative.first},"phoneNumber":"$phone","participantIds":[$group],""" +
                    """"groupParticipantIds":[$group],"createdIds":[${created.joinToString(",")}]}"""
            }
            if (smsTries.getValue(phone) >= 2) return 409 to "{}"
            // User처럼 대표자를 빼고 동행자끼리 이름·구분·학교로 같은 사람을 가린다.
            val added =
                people
                    .filter { node -> party.drop(1).none { it.second == node["name"].asString() && details[it.first] == detail(node) } }
                    .map { node -> (nextId.getAndIncrement() to node["name"].asString()).also { details[it.first] = detail(node) } }
            party.addAll(added)
            smsTries.merge(phone, 1, Int::plus)
            val texted = (if (added.isEmpty()) party else added).joinToString(",") { it.first.toString() }
            val group = party.joinToString(",") { it.first.toString() }
            val created = added.joinToString(",") { it.first.toString() }
            return 201 to
                """{"participantId":${party.first().first},"phoneNumber":"$phone","participantIds":[$texted],""" +
                """"groupParticipantIds":[$group],"createdIds":[$created]}"""
        }

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
                                val party =
                                    parties[
                                        json
                                            .readTree(
                                                requestBody,
                                            )["phoneNumber"]
                                            .asString(),
                                    ].orEmpty().filterNot { it.first in released }
                                val people =
                                    party.mapIndexed { index, (id, name) ->
                                        val (occupation, school) = details[id] ?: ("GENERAL" to null)
                                        val quoted = school?.let { "\"$it\"" }
                                        val schoolField = if (reportSchool) ""","school":$quoted""" else ""
                                        """{"participantId":$id,"name":"$name","occupation":"$occupation",""" +
                                            """"representative":${index == 0},"applicationType":"PRE"$schoolField}"""
                                    }
                                200 to """{"registered":${party.isNotEmpty()},"participants":[${people.joinToString(",")}]}"""
                            }

                            path == "/internal/standard-participants/details" -> {
                                val ids = json.readTree(requestBody)["participantIds"].toList().map { it.asLong() }
                                200 to
                                    ids.joinToString(",", "[", "]") {
                                        """{"participantId":$it,"occupation":"${details[it]?.first ?: "GENERAL"}"}"""
                                    }
                            }

                            path.contains("cancellation-status") -> {
                                503 to "{}"
                            }

                            path.endsWith("/preregister-session") || path.contains("/standard-registrations/") ||
                                path.contains("cancellation-guard/") -> {
                                204 to ""
                            }

                            path == "/internal/standard-participants" -> {
                                saveParticipants(json.readTree(requestBody))
                            }

                            path.contains("/preregister-sessions/") -> {
                                200 to definition(path.substringAfterLast('/').toLong())
                            }

                            path.startsWith("/internal/forms/") -> {
                                val today = OffsetDateTime.now()
                                200 to
                                    """{"id":"$EXPO","startDate":"${today.minusDays(1)}","endDate":"${today.plusDays(1)}",""" +
                                    """"dynamicForm":[{"id":1,"title":"companions","formType":"COMPANION","jsonData":{},""" +
                                    """"otherJson":{"maxSelection":${maxSelection.get()}},"dynamicFormType":"DEFAULT"}""" +
                                    // Target sessions (#173) need the representative occupation question.
                                    (
                                        if (target.get() == null) {
                                            ""
                                        } else {
                                            """,{"id":2,"title":"occupation","formType":"SELECT","jsonData":{},"otherJson":null,""" +
                                                """"dynamicFormType":"OCCUPATION"},{"id":3,"title":"school","formType":"SENTENCE",""" +
                                                """"jsonData":{},"otherJson":null,"dynamicFormType":"SCHOOL"}"""
                                        }
                                    ) + "]" + (occupationOpenAt.get()?.let { ""","occupationOpenAt":"$it"""" } ?: "") + "}"
                            }

                            else -> {
                                val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
                                200 to
                                    """{"startedDay":"$today","finishedDay":"$today","participantPhoneVerificationRequired":false,"participantPhoneVerificationRevision":0}"""
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
