package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.repository.AdminPreregistrationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.impl.PreregisterRecoveryScheduler
import team.startup.application.domain.application.service.impl.PreregisterWaitlist
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
class PreregisterWaitlistHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var recovery: PreregisterRecoveryScheduler

    @Autowired private lateinit var repository: PreregisterSessionRepository

    @Autowired private lateinit var waitlist: PreregisterWaitlist

    @Autowired private lateinit var context: org.springframework.context.ApplicationContext

    @Autowired private lateinit var ledger: AdminPreregistrationRepository

    @MockitoBean private lateinit var clock: Clock

    private val now = AtomicReference<Instant>()

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_promotion_offer, tb_preregister_reservation, tb_preregister_count_outbox, tb_session_attendance_outbox, " +
                "tb_preregister_transition, tb_preregister_request, tb_preregister_application, tb_preregister_session_change, " +
                "tb_preregister_session_state, tb_preregister_deleted_expo RESTART IDENTITY CASCADE",
        )
        userRequests.clear()
        capacity.set(1)
        waitingCapacity.set(5)
        closed = false
        revision.set(1)
        startedAt.set(Instant.parse("2030-01-01T00:00:00Z"))
        verifiedSessionPhone = "01000000002"
        cancelAccess = 200
        now.set(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        `when`(clock.instant()).thenAnswer { now.get() }
        `when`(clock.zone).thenReturn(ZoneOffset.UTC)
    }

    private fun submit(
        key: String?,
        people: Int = 1,
        phone: String,
        session: Long = 1,
    ): MockHttpServletResponse {
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
        return mvc.perform(request).andReturn().response
    }

    private fun waiting(
        response: MockHttpServletResponse,
        position: Int,
    ) {
        assertEquals(202, response.status)
        assertEquals(
            mapper.readTree("""{"status":"WAITING","sessionId":1,"position":$position}"""),
            mapper.readTree(response.contentAsString),
        )
    }

    private fun capacity(): JsonNode =
        mapper.readTree(
            mvc
                .perform(get("/application/expos/$EXPO/preregister-sessions/1/capacity"))
                .andReturn()
                .response.contentAsString,
        )

    private fun withdrawnActor(key: String): String? =
        jdbc.queryForObject("SELECT withdrawn_actor_type FROM tb_preregister_reservation WHERE request_id = ?", String::class.java, key)

    private fun state(key: String): String =
        jdbc.queryForObject("SELECT state FROM tb_preregister_reservation WHERE request_id = ?", String::class.java, key)!!

    private fun internal(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder): Int =
        mvc
            .perform(request.header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .response.status

    private fun cancel(applicationId: Long) {
        assertEquals(
            200,
            internal(
                delete("/internal/expos/$EXPO/preregister-sessions/1/applications").content("""{"applicationIds":[$applicationId]}"""),
            ),
        )
        waitlist.flush()
    }

    private fun withdraw(key: String) =
        internal(post("/internal/expos/$EXPO/preregister-reservations/$key/withdrawal")).also {
            waitlist.flush()
        }

    private fun adminCancel(
        vararg keys: String,
        authorization: String? = "Bearer admin-jwt",
    ): Int =
        mvc
            .perform(
                post("/application/admin/expos/$EXPO/preregistrations/cancellation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("keys" to keys.toList())))
                    .apply { authorization?.let { header("Authorization", it) } },
            ).andReturn()
            .response.status
            .also { waitlist.flush() }

    private fun sweep() {
        recovery.promote()
        waitlist.flush()
    }

    private fun reservation(key: String): JsonNode =
        mapper.readTree(
            mvc
                .perform(get("/internal/expos/$EXPO/preregister-reservations/$key").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.contentAsString,
        )

    private fun ownerWaitlist(): MockHttpServletResponse =
        mvc
            .perform(get("/application/expos/$EXPO/my-applications/preregister-waitlist").cookie(Cookie("PARTICIPANT_SESSION", "owner")))
            .andReturn()
            .response

    private fun change(close: Boolean) {
        val next = revision.get() + 1
        val definition = mapper.readTree(definition(1)) as tools.jackson.databind.node.ObjectNode
        definition.put("closed", close).put("revision", next)
        val body = mapper.writeValueAsString(mapOf("operation" to "UPDATE", "definitionChanged" to false, "definition" to definition))
        assertEquals(204, internal(put("/internal/expos/$EXPO/preregister-sessions/1/changes/$next").content(body)))
        closed = close
        revision.set(next)
        val reconciled =
            mvc.perform(
                post("/internal/expos/$EXPO/preregister-sessions/1/reconciliation").header("X-Internal-Token", "test-token"),
            )
        assertEquals("true", reconciled.andReturn().response.contentAsString)
        waitlist.flush()
    }

    // #144: a party waiting for a session that starts tomorrow (KST) gets an offer instead of an automatic promotion.
    private fun offerDay() {
        val kst = java.time.ZoneId.of("Asia/Seoul")
        now.set(
            LocalDate
                .now(kst)
                .atTime(10, 0)
                .atZone(kst)
                .toInstant(),
        )
        startedAt.set(now.get().plus(20, ChronoUnit.HOURS))
        capacity.set(2)
    }

    private fun offer(key: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM tb_promotion_offer WHERE request_id = ? ORDER BY created_at DESC LIMIT 1", key)

    private fun freeSession() {
        assertEquals(
            200,
            internal(delete("/internal/expos/$EXPO/preregister-sessions/1/applications").content("""{"applicationIds":[1,2]}""")),
        )
        waitlist.flush()
    }

    private fun accepted(offer: Map<String, Any?>) =
        assertEquals(
            200,
            internal(
                post("/internal/promotion-offers/${offer["id"]}/deliveries").content(
                    """{"eventId":"${offer["event_id"]}","status":"ACCEPTED","attemptId":"${UUID(1, 1)}",""" +
                        """"attemptedAt":"${now.get().minusSeconds(1)}","providerAcceptedAt":"${now.get()}"}""",
                ),
            ),
        )

    private fun respond(
        offer: Map<String, Any?>,
        action: String,
    ): MockHttpServletResponse =
        mvc
            .perform(
                post("/application/expos/$EXPO/my-applications/promotion-offers/${offer["id"]}/$action")
                    .with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                            .csrf(),
                    ).cookie(Cookie("PARTICIPANT_SESSION", "owner")),
            ).andReturn()
            .response

    @Test
    fun `day before the session a freed seat only holds an offer that the verified owner must accept before User delivery`() {
        offerDay()
        assertEquals(201, submit("a", 2, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        freeSession()
        assertEquals("WAITING", state("b"))
        val offer = offer("b")
        assertEquals("SCHEDULED", offer["state"])
        assertEquals(2, offer["seats"])
        assertEquals(0, capacity().get("remaining").intValue())
        assertEquals(2L, repository.occupied(EXPO, 1))
        recovery.recover()
        assertEquals(1, userRequests.size)
        val listed =
            mvc
                .perform(get("/application/expos/$EXPO/my-applications/promotion-offers").cookie(Cookie("PARTICIPANT_SESSION", "owner")))
                .andReturn()
                .response
        assertEquals(
            "PENDING",
            mapper
                .readTree(listed.contentAsString)
                .get(0)
                .get("status")
                .stringValue(),
        )
        assertEquals(409, respond(offer, "acceptance").status)
        accepted(offer)
        verifiedSessionPhone = "01000000009"
        assertEquals(404, respond(offer, "acceptance").status)
        verifiedSessionPhone = "01000000002"
        assertEquals(200, respond(offer, "acceptance").status)
        assertEquals("ACCEPTED", mapper.readTree(respond(offer, "acceptance").contentAsString).get("status").stringValue())
        assertEquals("RESERVED", state("b"))
        recovery.recover()
        assertEquals(2, userRequests.size)
        assertEquals("COMPLETED", state("b"))
    }

    @Test
    fun `decline and no response withdraw the waiting party with their actor and offer the next party`() {
        offerDay()
        assertEquals(201, submit("a", 2, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        waiting(submit("c", 2, "01000000003"), 2)
        freeSession()
        accepted(offer("b"))
        assertEquals(200, respond(offer("b"), "decline").status)
        assertEquals(200, respond(offer("b"), "decline").status)
        waitlist.flush()
        assertEquals("REJECTED", state("b"))
        assertEquals("USER", withdrawnActor("b"))
        assertEquals("SCHEDULED", offer("c")["state"])
        accepted(offer("c"))
        val scheduler = context.getBean(team.startup.application.domain.application.service.impl.PromotionOfferScheduler::class.java)
        now.set(now.get().plus(6, ChronoUnit.HOURS).minusNanos(1000))
        scheduler.sweep()
        assertEquals("WAITING", state("c"))
        now.set(now.get().plusNanos(1000))
        scheduler.sweep()
        waitlist.flush()
        assertEquals("REJECTED", state("c"))
        assertEquals("SYSTEM", withdrawnActor("c"))
        assertEquals("EXPIRED", offer("c")["state"])
        assertEquals(1, userRequests.size)
    }

    private fun check(offer: Map<String, Any?>): JsonNode =
        mapper.readTree(
            mvc
                .perform(
                    post("/internal/promotion-offers/check")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":1,"eventId":"${offer["event_id"]}","offerId":"${offer["id"]}","expoId":"$EXPO"}"""),
                ).andReturn()
                .response
                .also { assertEquals(200, it.status) }
                .contentAsString,
        )

    private fun stored(
        place: String,
        name: String,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_session_state SET definition = jsonb_set(definition::jsonb, '{place}', to_jsonb(?::text))::text",
            place,
        )
        jdbc.update(
            "UPDATE tb_preregister_reservation SET user_request = jsonb_set(user_request::jsonb, '{name}', to_jsonb(?::text))::text WHERE request_id = 'b'",
            name,
        )
    }

    // #236: Notification `promotionOfferCheckSchema` labels (trimmed, UTF-16 length, no \p{C}\p{Zl}\p{Zp}).
    private fun notificationLabel(
        value: JsonNode,
        max: Int,
    ) {
        val text = value.stringValue().trim()
        assert(text.isNotEmpty() && text.length <= max && Regex("^[^\\p{C}\\p{Zl}\\p{Zp}]+$").matches(text)) { text }
    }

    @Test
    fun `offer check turns place and name breaks and invisible characters into the Notification label contract`() {
        offerDay()
        assertEquals(201, submit("a", 2, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        freeSession()
        val offer = offer("b")
        val cases =
            listOf(
                Triple("1층\n강당", "per\u200Bson\r\n", "1층 강당"),
                Triple("\t본관\u2028\u2029 3층\u0007 \uDB40\uDC01강당\u200D ", "\u00A0person\u2060", "본관  3층  강당"),
                Triple("\uD83D\uDE00".repeat(127) + "가", "person", "\uD83D\uDE00".repeat(127) + "가"),
                Triple("\uD83D\uDE00".repeat(128), "person", null),
                Triple("가".repeat(256), "person", null),
                Triple("\u200B\n\u00A0", "person", null),
            )
        for ((place, name, location) in cases) {
            stored(place, name)
            val check = check(offer)
            assertEquals(true, check.get("eligible").booleanValue(), place)
            assertEquals("person", check.get("recipientName").stringValue())
            assertEquals("Morning", check.get("title").stringValue())
            assertEquals(location, check.get("location").takeUnless { it.isNull }?.stringValue(), place)
            notificationLabel(check.get("recipientName"), 100)
            notificationLabel(check.get("title"), 100)
            if (location != null) notificationLabel(check.get("location"), 255)
        }
        assertEquals("SCHEDULED", offer("b")["state"])
        assertEquals("WAITING", state("b"))
    }

    @Test
    fun `an offer whose recipient name has nothing sendable fails delivery instead of retrying and keeps its seats`() {
        offerDay()
        assertEquals(201, submit("a", 2, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        freeSession()
        val offer = offer("b")
        stored("1층\n강당", "\u200B\u00A0\n")
        val check = check(offer)
        assertEquals(false, check.get("eligible").booleanValue())
        assertEquals("NOT_CURRENT_OFFER", check.get("reason").stringValue())
        val failed = offer("b")
        assertEquals("DELIVERY_FAILED", failed["state"])
        assertEquals("INVALID_MESSAGE_TEXT", failed["delivery_reason"])
        assertEquals(now.get().plus(6, ChronoUnit.HOURS), (failed["expires_at"] as java.sql.Timestamp).toInstant())
        assertEquals("NOT_CURRENT_OFFER", check(offer).get("reason").stringValue())
        assertEquals("WAITING", state("b"))
        assertEquals(2L, repository.occupied(EXPO, 1))
        // The in-app response still works; restore the definition Expo approved before acceptance rechecks it.
        stored("Place", "person")
        assertEquals(200, respond(offer, "acceptance").status)
        assertEquals("RESERVED", state("b"))
    }

    @Test
    fun `short confirmed seats queue the whole party without User delivery and same key retries do not requeue`() {
        capacity.set(2)
        waitingCapacity.set(3)
        assertEquals(201, submit("a", 2, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        waiting(submit("c", 1, "01000000003"), 2)
        val full = submit("d", 1, "01000000004")
        assertEquals(409, full.status)
        assertEquals("SEATS_UNAVAILABLE", mapper.readTree(full.contentAsString)["code"].asString())
        waiting(submit("b", 2, "01000000002"), 1)
        recovery.recover()
        assertEquals(1, userRequests.size)
        val available = capacity()
        assertEquals(0, available.get("remaining").intValue())
        assertEquals(2, available.get("confirmed").intValue())
        assertEquals(3, available.get("waiting").intValue())
        assertEquals(3, available.get("appliedWaitingCapacity").intValue())
        assertEquals(0, available.get("maxWaitingApplicants").intValue())
        assertEquals("ADOPTED", available.get("waitingCapacityStatus").stringValue())
    }

    @Test
    fun `strict FIFO holds smaller parties until the blocking head withdraws`() {
        capacity.set(3)
        waitingCapacity.set(10)
        assertEquals(201, submit("a", 3, "01000000001").status)
        waiting(submit("b", 3, "01000000002"), 1)
        waiting(submit("c", 1, "01000000003"), 2)
        cancel(2) // a companion; the representative must cancel last
        assertEquals("WAITING", state("b"))
        assertEquals("WAITING", state("c"))
        val session = repository.adminSummary(EXPO).sessions.single()
        assertEquals(4, session.waitingParticipants)
        assertEquals(2, session.waitingReservations)
        assertEquals(3, session.waitingHeadApplicants)
        assertEquals(1, session.freeConfirmedSeats)
        assertEquals(10, session.appliedWaitingCapacity)
        assertEquals("HEAD_TOO_LARGE", session.promotionBlockedReason)
        assertEquals(2, reservation("c").get("position").intValue())
        assertEquals(204, withdraw("b"))
        assertEquals("RESERVED", state("c"))
        assertEquals(true, reservation("b").get("withdrawn").booleanValue())
        recovery.recover()
        assertEquals(2, userRequests.size)
        assertEquals(201, submit("c", 1, "01000000003").status)
        val withdrawn = submit("b", 3, "01000000002")
        assertEquals(409, withdrawn.status)
        assertEquals(mapper.readTree("""{"status":409,"code":"WAITLIST_WITHDRAWN"}"""), mapper.readTree(withdrawn.contentAsString))
        assertEquals(204, withdraw("b"))
        assertEquals(409, withdraw("c"))
        assertEquals(2, userRequests.size)
        // The internal path carries no authenticated identity, and the replay keeps the first record.
        assertEquals("UNKNOWN", withdrawnActor("b"))
    }

    @Test
    fun `seats freed after the application period stay empty until the scheduler sees an open period`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 1, "01000000002"), 1)
        now.set(now.get().plus(2, ChronoUnit.DAYS))
        cancel(1)
        assertEquals("WAITING", state("b"))
        sweep()
        assertEquals("WAITING", state("b"))
        now.set(Instant.now())
        sweep()
        assertEquals("RESERVED", state("b"))
        recovery.recover()
        assertEquals(201, submit("b", 1, "01000000002").status)
    }

    @Test
    fun `manual close blocks promotion and reopening promotes`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 1, "01000000002"), 1)
        change(close = true)
        assertEquals("SESSION_CLOSED", mapper.readTree(submit("c", 1, "01000000003").contentAsString)["code"].asString())
        cancel(1)
        assertEquals("WAITING", state("b"))
        assertEquals(
            "CLOSED",
            repository
                .adminSummary(EXPO)
                .sessions
                .single()
                .promotionBlockedReason,
        )
        change(close = false)
        assertEquals("RESERVED", state("b"))
    }

    @Test
    fun `promotion stops exactly at session start`() {
        // An hour before the start is inside the #144 offer window, so the last possible step is a seat-holding offer.
        offerDay()
        capacity.set(1)
        startedAt.set(now.get().plus(1, ChronoUnit.HOURS))
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 1, "01000000002"), 1)
        now.set(startedAt.get())
        cancel(1)
        assertEquals("WAITING", state("b"))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_promotion_offer", Long::class.java))
        now.set(startedAt.get().minusSeconds(1))
        sweep()
        assertEquals("WAITING", state("b"))
        assertEquals("SCHEDULED", offer("b")["state"])
    }

    @Test
    fun `owner finds and withdraws the waiting party with the verified session phone`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 2, "010-0000-0002"), 1)
        val lookup =
            mvc
                .perform(
                    get("/application/expos/$EXPO/my-applications/preregister-waitlist").cookie(Cookie("PARTICIPANT_SESSION", "owner")),
                ).andReturn()
        assertEquals(200, lookup.response.status)
        assertEquals(
            mapper.readTree("""{"sessionId":1,"applicants":2,"status":"WAITING","position":1,"reviewRequired":false}"""),
            mapper.readTree(lookup.response.contentAsString),
        )
        val withdrawal =
            mvc
                .perform(
                    post("/application/expos/$EXPO/my-applications/preregister-waitlist/cancellation")
                        // The shared context may hold the session-backed repository installed by csrf() elsewhere.
                        .cookie(
                            *listOfNotNull(Cookie("PARTICIPANT_SESSION", "owner"), lookup.response.getCookie("XSRF-TOKEN")).toTypedArray(),
                        ).session(lookup.request.session as MockHttpSession)
                        .header("X-CSRF-TOKEN", requireNotNull(lookup.response.getHeader("X-CSRF-TOKEN"))),
                ).andReturn()
        assertEquals(204, withdrawal.response.status)
        assertEquals("USER", withdrawnActor("b"))
        val after =
            mvc.perform(
                get("/application/expos/$EXPO/my-applications/preregister-waitlist").cookie(Cookie("PARTICIPANT_SESSION", "owner")),
            )
        assertEquals(404, after.andReturn().response.status)
        assertEquals(409, submit("b", 2, "010-0000-0002").status)
        waiting(submit("b-again", 2, "010-0000-0002"), 1)
        assertEquals(1, userRequests.size)
    }

    @Test
    fun `owner lists waiting parties per session and withdraws only the selected session`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 2, "010-0000-0002"), 1)
        // #198: a later session wait of the same party (as #199 would add) gets its own queue position.
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "SELECT expo_id, 'b2', 2, command || '2', user_request, applicants, 'WAITING', nextval('seq_preregister_waitlist') " +
                "FROM tb_preregister_reservation WHERE request_id = 'b'",
        )
        val owner = Cookie("PARTICIPANT_SESSION", "owner")
        val lookup = mvc.perform(get("/application/expos/$EXPO/my-applications/preregister-waitlists").cookie(owner)).andReturn()
        assertEquals(200, lookup.response.status)
        assertEquals(
            mapper.readTree(
                """[{"sessionId":2,"applicants":2,"status":"WAITING","position":1,"reviewRequired":false},""" +
                    """{"sessionId":1,"applicants":2,"status":"WAITING","position":1,"reviewRequired":false}]""",
            ),
            mapper.readTree(lookup.response.contentAsString),
        )
        val first = mvc.perform(get("/application/expos/$EXPO/my-applications/preregister-waitlist?sessionId=1").cookie(owner)).andReturn()
        assertEquals(1L, mapper.readTree(first.response.contentAsString)["sessionId"].asLong())
        val withdrawal =
            mvc
                .perform(
                    post("/application/expos/$EXPO/my-applications/preregister-waitlist/cancellation?sessionId=1")
                        .cookie(*listOfNotNull(owner, lookup.response.getCookie("XSRF-TOKEN")).toTypedArray())
                        .session(lookup.request.session as MockHttpSession)
                        .header("X-CSRF-TOKEN", requireNotNull(lookup.response.getHeader("X-CSRF-TOKEN"))),
                ).andReturn()
        assertEquals(204, withdrawal.response.status)
        assertEquals("REJECTED", state("b"))
        assertEquals("WAITING", state("b2"))
        val after = mvc.perform(get("/application/expos/$EXPO/my-applications/preregister-waitlists").cookie(owner)).andReturn()
        assertEquals(
            mapper.readTree("""[{"sessionId":2,"applicants":2,"status":"WAITING","position":1,"reviewRequired":false}]"""),
            mapper.readTree(after.response.contentAsString),
        )
    }

    @Test
    fun `owner sees direct reservations still being saved or held for review but cannot withdraw them`() {
        // #252: a direct reservation has no queue_seq and no User participant yet, but its phone already answers 409.
        verifiedSessionPhone = "01000000003"
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)

        // Rows of the verified phone; each starts RESERVED, then takes its own state in one update (V17 checks).
        fun copy(
            key: String,
            session: Long,
            state: String,
            extra: String = "",
            expo: UUID = EXPO,
        ) {
            jdbc.update(
                "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants) " +
                    "SELECT ?, ?, ?, command || ?, jsonb_set(user_request::jsonb, '{phoneNumber}', '\"010-0000-0003\"')::text, 2 " +
                    "FROM tb_preregister_reservation WHERE request_id = 'a'",
                expo,
                key,
                session,
                key,
            )
            val columns = if (extra.isEmpty()) "state = ?" else "state = ?, $extra"
            jdbc.update("UPDATE tb_preregister_reservation SET $columns WHERE request_id = ?", state, key)
        }
        copy("w", 2, "WAITING", "queue_seq = nextval('seq_preregister_waitlist')")
        copy("direct", 3, "RESERVED")
        copy("review", 4, "RESERVED", "error_status = 409, next_attempt_at = 'infinity'")
        copy(
            "promoted-review",
            5,
            "RESERVED",
            "queue_seq = nextval('seq_preregister_waitlist'), error_status = 502, next_attempt_at = 'infinity'",
        )
        copy("done", 6, "COMPLETED")
        copy("rejected", 7, "REJECTED", "error_status = 409")
        copy("addition", 8, "RESERVED", "session_addition = TRUE")
        copy("companion", 9, "RESERVED", "companion_representative_id = 42")
        copy("other-expo", 10, "RESERVED", expo = UUID.fromString("00000000-0000-0000-0000-000000000098"))
        val occupied = repository.occupied(EXPO, 3)

        val owner = Cookie("PARTICIPANT_SESSION", "owner")
        val lookup = mvc.perform(get("/application/expos/$EXPO/my-applications/preregister-waitlists").cookie(owner)).andReturn()
        assertEquals(200, lookup.response.status)
        // Waitlisted rows keep their latest-first order; direct ones follow without a queue position.
        assertEquals(
            mapper.readTree(
                """[{"sessionId":5,"applicants":2,"status":"PROCESSING","position":null,"reviewRequired":true},""" +
                    """{"sessionId":2,"applicants":2,"status":"WAITING","position":1,"reviewRequired":false},""" +
                    """{"sessionId":4,"applicants":2,"status":"PROCESSING","position":null,"reviewRequired":true},""" +
                    """{"sessionId":3,"applicants":2,"status":"PROCESSING","position":null,"reviewRequired":false}]""",
            ),
            mapper.readTree(lookup.response.contentAsString),
        )
        val direct = mvc.perform(get("/application/expos/$EXPO/my-applications/preregister-waitlist?sessionId=3").cookie(owner)).andReturn()
        assertEquals(
            mapper.readTree("""{"sessionId":3,"applicants":2,"status":"PROCESSING","position":null,"reviewRequired":false}"""),
            mapper.readTree(direct.response.contentAsString),
        )

        fun ownerWithdraw(query: String) =
            mvc
                .perform(
                    post("/application/expos/$EXPO/my-applications/preregister-waitlist/cancellation$query")
                        .cookie(*listOfNotNull(owner, lookup.response.getCookie("XSRF-TOKEN")).toTypedArray())
                        .session(lookup.request.session as MockHttpSession)
                        .header("X-CSRF-TOKEN", requireNotNull(lookup.response.getHeader("X-CSRF-TOKEN"))),
                ).andReturn()
                .response.status
        // A direct reservation is not a waiting party: withdrawal never releases its seats.
        assertEquals(404, ownerWithdraw("?sessionId=3"))
        assertEquals(404, ownerWithdraw("?sessionId=4"))
        assertEquals("RESERVED", state("direct"))
        assertEquals("RESERVED", state("review"))
        assertEquals(occupied, repository.occupied(EXPO, 3))
        assertEquals(204, ownerWithdraw(""))
        assertEquals("REJECTED", state("w"))
        assertEquals("RESERVED", state("promoted-review"))
        // The phone stays held while the reservation is unresolved.
        assertEquals(409, submit("again", 1, "010-0000-0003", session = 3).status)

        verifiedSessionPhone = "01000000004"
        val other = mvc.perform(get("/application/expos/$EXPO/my-applications/preregister-waitlists").cookie(owner)).andReturn()
        assertEquals("[]", other.response.contentAsString)
        assertEquals(
            401,
            mvc
                .perform(get("/application/expos/$EXPO/my-applications/preregister-waitlists"))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `admin cancels one confirmed party or withdraws one waiting party with the verified admin ID`() {
        capacity.set(2)
        assertEquals(201, submit("a", 2, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        waiting(submit("c", 1, "01000000003"), 2)
        val representative = "APPLICATION:$EXPO:1"
        val companion = "APPLICATION:$EXPO:2"
        assertEquals(401, adminCancel(representative, authorization = null))
        cancelAccess = 403
        assertEquals(403, adminCancel(representative, companion))
        cancelAccess = 200
        assertEquals(400, adminCancel(*Array(6) { "APPLICATION:$EXPO:${it + 1}" }))
        assertEquals(404, adminCancel("APPLICATION:${UUID.randomUUID()}:1"))
        assertEquals(400, adminCancel(representative, "RESERVATION:$EXPO:c"))
        assertEquals(400, adminCancel("RESERVATION:$EXPO:b", "RESERVATION:$EXPO:c"))
        // #198: the representative's session is cancelled on its own; the companion keeps its seat.
        assertEquals(200, adminCancel(representative))
        assertEquals("WAITING", state("b"))
        assertEquals(200, adminCancel(representative, companion))
        val transitions = "SELECT actor_type || ':' || actor_id FROM tb_preregister_transition WHERE status = 'CANCELLED'"
        assertEquals(listOf("ADMIN:7", "ADMIN:7"), jdbc.queryForList(transitions, String::class.java))
        assertEquals("RESERVED", state("b"))
        assertEquals(200, adminCancel(representative, companion))
        assertEquals(2, jdbc.queryForList(transitions, String::class.java).size)
        assertEquals(409, adminCancel("RESERVATION:$EXPO:b"))
        assertEquals(200, adminCancel("RESERVATION:$EXPO:c"))
        assertEquals(200, adminCancel("RESERVATION:$EXPO:c"))
        assertEquals("ADMIN", withdrawnActor("c"))
        val rows =
            ledger
                .query(EXPO, AdminPreregistrationQuery(), null, 10, keys = listOf(representative, "RESERVATION:$EXPO:c"))
                .first
                .map { "${it.key}:${it.status}:${it.cancellationActorType}:${it.cancellationActorId}" }
        assertEquals(listOf("$representative:CANCELLED:ADMIN:7", "RESERVATION:$EXPO:c:CANCELLED:ADMIN:7"), rows)
    }

    @Test
    fun `concurrent cancellations promote each waiting party once without exceeding capacity`() {
        capacity.set(2)
        assertEquals(201, submit("a", 1, "01000000001").status)
        assertEquals(201, submit("b", 1, "01000000002").status)
        waiting(submit("c", 1, "01000000003"), 1)
        waiting(submit("d", 1, "01000000004"), 2)
        val pool = Executors.newFixedThreadPool(2)
        listOf(1L, 2L).map { id -> pool.submit { cancel(id) } }.forEach { it.get() }
        pool.shutdown()
        assertEquals("RESERVED", state("c"))
        assertEquals("RESERVED", state("d"))
        assertEquals(2L, repository.occupied(EXPO, 1))
        recovery.recover()
        assertEquals(4, userRequests.size)
    }

    @Test
    fun `started sessions stop queueing and stale waiting parties release the representative phone`() {
        startedAt.set(now.get().plus(1, ChronoUnit.HOURS))
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 1, "01000000002"), 1)
        now.set(startedAt.get())
        assertEquals(409, submit("c", 1, "01000000003").status)
        val ended = ownerWaitlist()
        assertEquals(200, ended.status)
        assertEquals(
            mapper.readTree("""{"sessionId":1,"applicants":1,"status":"PROMOTION_ENDED","position":null,"reviewRequired":false}"""),
            mapper.readTree(ended.contentAsString),
        )
        assertEquals(201, submit("other-session", 1, "01000000002", session = 2).status)
    }

    @Test
    fun `identical retries without an idempotency key return the queued receipt`() {
        assertEquals(201, submit(null, 1, "01000000001").status)
        waiting(submit(null, 2, "01000000002"), 1)
        waiting(submit(null, 2, "01000000002"), 1)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_reservation WHERE state = 'WAITING'", Int::class.java))
    }

    @Test
    fun `sessions with earlier applications apply waiting capacity only after explicit adoption`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        jdbc.update("UPDATE tb_preregister_session_state SET definition = (definition::jsonb - 'waitingCapacity')::text")
        val legacy = capacity()
        assertEquals("UNADOPTED", legacy.get("waitingCapacityStatus").stringValue())
        assertEquals(true, legacy.get("appliedWaitingCapacity").isNull)
        assertEquals(5, legacy.get("expoWaitingCapacity").intValue())
        assertEquals(409, submit("b", 1, "01000000002").status)
        val adopt = "/internal/expos/$EXPO/preregister-sessions/1/waiting-capacity"
        assertEquals(409, internal(put(adopt).content("""{"expected":4}""")))
        assertEquals(204, internal(put(adopt).content("""{"expected":5}""")))
        assertEquals(204, internal(put(adopt).content("""{"expected":5}""")))
        assertEquals("ADOPTED", capacity().get("waitingCapacityStatus").stringValue())
        waiting(submit("b", 1, "01000000002"), 1)
    }

    // #268: 신청 이력이 있으면 시간 항목만 잠그고, 대기 정원·제목·장소는 현재 대기 인원 이상이면 받는다.
    @Test
    fun `sessions with only cancelled history accept non time edits and lock times`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        cancel(1)
        assertEquals(204, prepare { it.put("waitingCapacity", 6) }.status)
        waitingCapacity.set(6)
        reconcile()
        conflict(prepare { it.put("startedAt", "2030-01-01T00:30:00Z") }, "TIME_LOCKED_BY_HISTORY")
        conflict(prepare { it.put("cancelEndedAt", "2029-12-31T00:00:00Z") }, "TIME_LOCKED_BY_HISTORY")
        conflict(prepare { it.put("endedAt", "2030-01-01T01:30:00Z") }, "TIME_LOCKED_BY_HISTORY")
        // #324: a same-day end shortening passes with the metadata edits.
        assertEquals(204, prepare { it.put("endedAt", "2030-01-01T00:30:00Z").put("title", "Evening").put("place", "Hall") }.status)
    }

    @Test
    fun `waiting capacity cannot drop below the waiting applicants`() {
        assertEquals(201, submit("a", 1, "01000000001").status)
        waiting(submit("b", 2, "01000000002"), 1)
        conflict(prepare { it.put("waitingCapacity", 1) }, "BELOW_WAITING_COUNT")
        assertEquals(204, prepare { it.put("waitingCapacity", 2) }.status)
    }

    private fun prepare(edit: (tools.jackson.databind.node.ObjectNode) -> Unit): MockHttpServletResponse {
        val next = revision.get() + 1
        val definition = (mapper.readTree(definition(1)) as tools.jackson.databind.node.ObjectNode).put("revision", next)
        edit(definition)
        val body = mapper.writeValueAsString(mapOf("operation" to "UPDATE", "definitionChanged" to true, "definition" to definition))
        return mvc
            .perform(
                put("/internal/expos/$EXPO/preregister-sessions/1/changes/$next")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andReturn()
            .response
    }

    private fun conflict(
        response: MockHttpServletResponse,
        code: String,
    ) {
        assertEquals(409, response.status)
        assertEquals(code, mapper.readTree(response.contentAsString)["code"].asString())
    }

    private fun reconcile() {
        revision.incrementAndGet()
        val reconciled =
            mvc.perform(post("/internal/expos/$EXPO/preregister-sessions/1/reconciliation").header("X-Internal-Token", "test-token"))
        assertEquals("true", reconciled.andReturn().response.contentAsString)
    }

    companion object {
        private val EXPO = UUID.fromString("00000000-0000-0000-0000-000000000099")
        private val capacity = AtomicInteger(1)
        private val waitingCapacity = AtomicInteger(5)
        private val revision = AtomicInteger(1)
        private val startedAt = AtomicReference(Instant.parse("2030-01-01T00:00:00Z"))

        @Volatile private var closed = false

        @Volatile private var verifiedSessionPhone = "01000000002"

        @Volatile private var cancelAccess = 200
        private val userRequests = CopyOnWriteArrayList<JsonNode>()
        private val json =
            tools.jackson.module.kotlin
                .jacksonObjectMapper()

        private fun definition(id: Long) =
            """{"id":$id,"expoId":"$EXPO","title":"Morning","startedAt":"${startedAt.get()}","endedAt":"2030-01-01T01:00:00Z",""" +
                """"place":"Place","capacity":${capacity.get()},"waitingCapacity":${waitingCapacity.get()},"closed":$closed,""" +
                """"revision":${revision.get()},"cancelEndedAt":"2030-01-01T00:00:00Z"}"""

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/") { exchange ->
                    val path = exchange.requestURI.path
                    val (status, body) =
                        when {
                            path.endsWith("/admin-application-cancel-access") -> {
                                cancelAccess to
                                    """{"expoId":"$EXPO","adminId":7,"permission":"APPLICATION_CANCEL",""" +
                                    """"policyVersion":"EXPO_ADMIN_APPLICATION_CANCEL_V1","scope":"EXPO_ADMIN"}"""
                            }

                            path == "/internal/participant-sessions/resolve" -> {
                                200 to """{"expoId":"$EXPO","phoneNumber":"$verifiedSessionPhone","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            // A waiting party's representative is not a User participant yet.
                            path == "/internal/standard-participants/managed" -> {
                                200 to """{"registered":false,"participants":[]}"""
                            }

                            path == "/internal/standard-participants/admin-cancellation-status" -> {
                                val id = json.readTree(exchange.requestBody.bufferedReader().readText()).path("participantId").asLong()
                                200 to """{"participantId":$id,"entered":false,"traineeLinked":false,"linkedTraineeId":null}"""
                            }

                            path.endsWith("/preregister-session") || path.contains("/standard-registrations/") ||
                                path.contains("cancellation-guard/") -> {
                                204 to ""
                            }

                            path == "/internal/standard-participants" -> {
                                val request = json.readTree(exchange.requestBody.bufferedReader().readText())
                                userRequests.add(request)
                                val first = 42L + 10 * (userRequests.size - 1)
                                val count = 1 + json.readTree(request.get("informationJson").stringValue()).get("companions").size()
                                val ids = (first until first + count).joinToString(",")
                                201 to
                                    """{"participantId":$first,"phoneNumber":"01000000000","participantIds":[$ids],"groupParticipantIds":[$ids]}"""
                            }

                            path.contains("/preregister-sessions/") -> {
                                200 to definition(path.substringAfterLast('/').toLong())
                            }

                            path.startsWith("/internal/forms/") -> {
                                val today = OffsetDateTime.now()
                                200 to
                                    """{"id":"$EXPO","startDate":"${today.minusDays(1)}","endDate":"${today.plusDays(1)}",""" +
                                    """"dynamicForm":[{"id":1,"title":"companions","formType":"COMPANION","jsonData":{},"otherJson":{"maxSelection":4},"dynamicFormType":"DEFAULT"}]}"""
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
            registry.add("application.promotion-offers.enabled") { true }
            registry.add("application.promotion-offers.bootstrap-servers") { "127.0.0.1:9" }
            registry.add("application.promotion-offers.response-url") { "https://client.example/application/forms/{expoId}" }
            registry.add("application.promotion-offers.delay-ms") { 3600000 }
        }
    }
}
