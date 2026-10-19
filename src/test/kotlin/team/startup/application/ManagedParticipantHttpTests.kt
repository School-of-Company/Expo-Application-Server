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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = ["eureka.client.enabled=false", "application.program-sessions.enabled=true", "application.attendance.enabled=true"],
)
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedParticipantHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE tb_standard_program_application, tb_training_program_application, tb_program_session_application CASCADE")
        jdbc.execute("TRUNCATE tb_owner_cancellation_delivery, tb_preregister_application, tb_preregister_session_state CASCADE")
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{\"startedAt\":\"2000-01-01T00:00:00Z\",\"endedAt\":\"2100-01-01T00:00:00Z\"}')",
            expo,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 1, 1, 'CONFIRMED'), (?, 1, 1, 2, 'CONFIRMED'), (?, 1, 1, 3, 'CANCELLED')",
            expo,
            expo,
            expo,
        )
        qrCalls.set(0)
        supplierStatus = 200
        registered = true
        duringQr = {}
        linkBodies.clear()
        pendingPolicy = false
        missingPolicy = false
        adminStatus = 200
    }

    @Test
    fun `eligibility agrees with submission despite saved representative and cancellation delivery`() {
        for (condition in listOf("representative", "ACQUIRING", "ABORT")) {
            setup()
            if (condition == "representative") {
                jdbc.update("UPDATE tb_preregister_application SET representative_id = 9 WHERE participant_id = 1")
            } else {
                cancellationDelivery(1, condition)
            }
            mvc
                .perform(get(participantsPath).cookie(cookie))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.participants[0].eligible").value(true))
            mvc
                .perform(
                    post("/application/expos/$expo/program-sessions/STANDARD/1/applications")
                        .with(csrf())
                        .cookie(cookie)
                        .header("Idempotency-Key", "eligibility-$condition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"participantId":1,"formVersion":1,"answers":{}}"""),
                ).andExpect(status().isCreated)
        }
    }

    @Test
    fun `aborted cancellation preserves confirmed participant and QR access`() {
        cancellationDelivery(1, "ABORT")
        mvc
            .perform(get(participantsPath).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participants[0].eligible").value(true))
        mvc
            .perform(get(qrPath(1)).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("private-code"))
        duringQr = { jdbc.update("UPDATE tb_owner_cancellation_delivery SET action = 'ACQUIRING'") }
        mvc.perform(get(qrPath(1)).cookie(cookie)).andExpect(status().isNotFound)
        assertEquals(2, qrCalls.get())
        jdbc.update("UPDATE tb_owner_cancellation_delivery SET action = 'COMMIT', cancellation_revision = 1")
        mvc.perform(get(qrPath(1)).cookie(cookie)).andExpect(status().isNotFound)
        assertEquals(2, qrCalls.get())
    }

    @Test
    fun `pending revision allows policy reads while submission remains blocked and missing program is not found`() {
        pendingPolicy = true
        for (type in listOf("STANDARD", "TRAINING")) {
            mvc
                .perform(get(participantsPath.replace("STANDARD", type)).cookie(cookie))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.participants[0].eligible").value(true))
            mvc
                .perform(
                    post("/application/expos/$expo/program-sessions/$type/1/applications")
                        .with(csrf())
                        .cookie(cookie)
                        .header("Idempotency-Key", "pending-$type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"participantId":1,"formVersion":1,"answers":{}}"""),
                ).andExpect(status().isConflict)
        }
        missingPolicy = true
        mvc.perform(get(participantsPath).cookie(cookie)).andExpect(status().isNotFound)
    }

    @Test
    fun `confirmed trainee submission forwards optional form training ID and replay reuses link`() {
        val path = "/application/expos/$expo/program-sessions/TRAINING/1/applications"

        fun submit(
            participant: Int,
            answer: String,
        ) = post(path)
            .with(csrf())
            .cookie(cookie)
            .header("Idempotency-Key", "training-$participant")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"participantId":$participant,"formVersion":1,"answers":$answer}""")
        mvc.perform(submit(1, """{"7":"academy-1"}""")).andExpect(status().isCreated).andExpect(jsonPath("$.traineeId").value(501))
        mvc.perform(submit(1, """{"7":"academy-1"}""")).andExpect(status().isCreated).andExpect(jsonPath("$.traineeId").value(501))
        mvc.perform(submit(2, "{}")).andExpect(status().isCreated).andExpect(jsonPath("$.traineeId").value(502))
        assertEquals(2, linkBodies.size)
        assertEquals("academy-1", ObjectMapper().readTree(linkBodies[0]).path("trainingId").stringValue())
        org.junit.jupiter.api.Assertions
            .assertTrue(ObjectMapper().readTree(linkBodies[1]).path("trainingId").isNull)
        mvc
            .perform(get("/internal/program-applications/training/1/trainees/501").header("X-Internal-Token", "test-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.applied").value(true))
    }

    @Test
    fun `managed list marks confirmed participants outside the program session time`() {
        // 프로그램 1은 09-10 KST, 회차는 13-15 KST라 겹치지 않는다(#197).
        jdbc.update(
            "UPDATE tb_preregister_session_state SET definition = ? WHERE expo_id = ?",
            """{"startedAt":"2030-01-01T04:00:00Z","endedAt":"2030-01-01T06:00:00Z"}""",
            expo,
        )
        mvc
            .perform(get(participantsPath).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participants[0].eligible").value(false))
            .andExpect(jsonPath("$.participants[0].ineligibleReason").value("SESSION_TIME_MISMATCH"))
            .andExpect(jsonPath("$.participants[2].ineligibleReason").value("PREREGISTRATION_NOT_CONFIRMED"))
    }

    @Test
    fun `managed list uses stored roles and local confirmed state without exposing QR or phone`() {
        val result =
            mvc
                .perform(get(participantsPath).cookie(cookie))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.participants[0].eligible").value(true))
                .andExpect(jsonPath("$.participants[1].eligible").value(false))
                .andExpect(jsonPath("$.participants[2].eligible").value(false))
                .andExpect(jsonPath("$.participants[0].ineligibleReason").isEmpty)
                .andExpect(jsonPath("$.participants[1].ineligibleReason").value("OCCUPATION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.participants[2].ineligibleReason").value("PREREGISTRATION_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.participants[0].participant.code").doesNotExist())
                .andExpect(jsonPath("$.participants[0].participant.phoneNumber").doesNotExist())
                .andReturn()
        org.junit.jupiter.api.Assertions
            .assertFalse(result.response.contentAsString.contains("private-code"))
        registered = false
        mvc
            .perform(get(participantsPath).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registered").value(false))
            .andExpect(jsonPath("$.participants").isEmpty)
        assertEquals(0, qrCalls.get())
    }

    @Test
    fun `group reasons reflect active ledger and final submission rechecks after advisory lookup`() {
        mvc
            .perform(get(participantsPath).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participants[0].eligible").value(true))

        fun submit(key: String) =
            post("/application/expos/$expo/program-sessions/STANDARD/1/applications")
                .with(csrf())
                .cookie(cookie)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"participantId":1,"formVersion":1,"answers":{}}""")
        mvc.perform(submit("first")).andExpect(status().isCreated)
        mvc
            .perform(get(participantsPath).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capacity.capacity").value(5))
            .andExpect(jsonPath("$.capacity.confirmedCount").value(1))
            .andExpect(jsonPath("$.capacity.remaining").value(4))
            .andExpect(jsonPath("$.capacity.waitingCapacity").value(0))
            .andExpect(jsonPath("$.capacity.waitingCount").value(0))
            .andExpect(jsonPath("$.capacity.waitingRemaining").value(0))
        for (state in listOf("CONFIRMED", "WAITING")) {
            jdbc.update("UPDATE tb_program_session_application SET status = ?", state)
            mvc
                .perform(get(participantsPath).cookie(cookie))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.participants[0].eligible").value(false))
                .andExpect(jsonPath("$.participants[0].ineligibleReason").value("GROUP_ALREADY_APPLIED"))
                .andExpect(jsonPath("$.capacity.waitingCount").value(if (state == "WAITING") 1 else 0))
            mvc
                .perform(submit("duplicate-$state"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("APPLICATION_CONFLICT"))
                .andExpect(jsonPath("$.ineligibleReason").value("GROUP_ALREADY_APPLIED"))
        }
        mvc.perform(submit("first")).andExpect(status().isCreated)
        jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED'")
        jdbc.update("DELETE FROM tb_standard_program_application")
        mvc
            .perform(get(participantsPath).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participants[0].eligible").value(true))
    }

    @Test
    fun `submission eligibility reasons do not expose supplier details`() {
        for ((participant, reason) in listOf(2 to "OCCUPATION_NOT_ALLOWED", 3 to "PREREGISTRATION_NOT_CONFIRMED")) {
            mvc
                .perform(
                    post("/application/expos/$expo/program-sessions/STANDARD/1/applications")
                        .with(csrf())
                        .cookie(cookie)
                        .header("Idempotency-Key", "denied-$participant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"participantId":$participant,"formVersion":1,"answers":{}}"""),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("PARTICIPANT_INELIGIBLE"))
                .andExpect(jsonPath("$.ineligibleReason").value(reason))
        }
    }

    @Test
    fun `QR checks owner and confirmed state before Attendance and rejects cancellation immediately`() {
        repeat(2) {
            mvc
                .perform(get(qrPath(2)).cookie(cookie))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.participantId").value(2))
                .andExpect(jsonPath("$.code").value("private-code"))
        }
        assertEquals(2, qrCalls.get())
        for (id in listOf(3L, 99L)) mvc.perform(get(qrPath(id)).cookie(cookie)).andExpect(status().isNotFound)
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 2")
        mvc.perform(get(qrPath(2)).cookie(cookie)).andExpect(status().isNotFound)
        mvc.perform(get(qrPath(1).replace(expo.toString(), UUID.randomUUID().toString())).cookie(cookie)).andExpect(status().isBadGateway)
        assertEquals(2, qrCalls.get())
    }

    @Test
    fun `pending cancellation and cancellation during QR retrieval cannot expose code`() {
        jdbc.update(
            "INSERT INTO tb_owner_cancellation_delivery " +
                "(expo_id, application_type, application_id, participant_id, operation_id, action, next_attempt_at) " +
                "SELECT expo_id, 'PREREGISTER', id::text, participant_id, ?, 'ACQUIRING', 'infinity' " +
                "FROM tb_preregister_application WHERE participant_id = 2",
            UUID.randomUUID(),
        )
        mvc.perform(get(qrPath(2)).cookie(cookie)).andExpect(status().isNotFound)
        assertEquals(0, qrCalls.get())
        duringQr = { jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 1") }
        mvc
            .perform(get(qrPath(1)).cookie(cookie))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("APPLICATION_TARGET_NOT_FOUND"))
        assertEquals(1, qrCalls.get())
    }

    @Test
    fun `cookie ambiguity expiry and supplier denial fail closed with no provider body`() {
        mvc.perform(get(qrPath(1))).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
        mvc.perform(get(qrPath(1)).cookie(cookie, cookie)).andExpect(status().isUnauthorized)
        mvc
            .perform(get(qrPath(1)).cookie(Cookie("PARTICIPANT_SESSION", "expired")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
        for (supplier in listOf(404, 503)) {
            supplierStatus = supplier
            mvc
                .perform(get(qrPath(1)).cookie(cookie))
                .andExpect(status().`is`(supplier))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-code"))))
        }
    }

    @Test
    fun `admin QR uses the confirmed ledger representative and rechecks cancellation`() {
        mvc.perform(get(adminQrPath(2))).andExpect(status().isUnauthorized)
        adminStatus = 403
        mvc.perform(get(adminQrPath(2)).header("Authorization", "Bearer admin")).andExpect(status().isForbidden)
        adminStatus = 200
        assertEquals(0, qrCalls.get())
        mvc
            .perform(get(adminQrPath(2)).header("Authorization", "Bearer admin"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.participantId").value(2))
            .andExpect(jsonPath("$.code").value("private-code"))
        assertEquals(1, qrCalls.get())
        for (id in listOf(3L, 99L)) mvc.perform(get(adminQrPath(id)).header("Authorization", "Bearer admin")).andExpect(status().isNotFound)
        assertEquals(1, qrCalls.get())
        duringQr = { cancellationDelivery(2, "ACQUIRING") }
        mvc.perform(get(adminQrPath(2)).header("Authorization", "Bearer admin")).andExpect(status().isNotFound)
        assertEquals(2, qrCalls.get())
        mvc.perform(get(adminQrPath(2)).header("Authorization", "Bearer admin")).andExpect(status().isNotFound)
        assertEquals(2, qrCalls.get())
        mvc.perform(get(adminQrPath(0)).header("Authorization", "Bearer admin")).andExpect(status().isBadRequest)
    }

    private fun adminQrPath(id: Long) = "/application/admin/expos/$expo/participants/$id/qr"

    private fun qrPath(id: Long) = "/application/expos/$expo/participants/$id/qr"

    private fun cancellationDelivery(
        participantId: Long,
        action: String,
    ) {
        jdbc.update(
            "INSERT INTO tb_owner_cancellation_delivery " +
                "(expo_id, application_type, application_id, participant_id, operation_id, action, next_attempt_at) " +
                "SELECT expo_id, 'PREREGISTER', id::text, participant_id, ?, ?, 'infinity' " +
                "FROM tb_preregister_application WHERE participant_id = ?",
            UUID.randomUUID(),
            action,
            participantId,
        )
    }

    private val participantsPath get() = "/application/expos/$expo/program-sessions/STANDARD/1/participants"
    private val cookie = Cookie("PARTICIPANT_SESSION", "verified")

    companion object {
        private val expo = UUID.randomUUID()
        private val qrCalls = AtomicInteger()
        private val linkBodies = mutableListOf<String>()

        @Volatile private var supplierStatus = 200

        @Volatile private var registered = true

        @Volatile private var duringQr: () -> Unit = {}

        @Volatile private var pendingPolicy = false

        @Volatile private var missingPolicy = false

        @Volatile private var adminStatus = 200
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val path = exchange.requestURI.path
                    var responseStatus = 200
                    val response =
                        when {
                            path.endsWith("participant-sessions/resolve") && body.contains("expired") -> {
                                responseStatus = 401
                                """{"code":"SESSION_EXPIRED"}"""
                            }

                            path.endsWith("participant-sessions/resolve") -> {
                                """{"expoId":"$expo","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            path.endsWith("managed") -> {
                                if (!registered) {
                                    """{"registered":false,"participants":[]}"""
                                } else {
                                    """{"registered":true,"participants":[{"participantId":1,"name":"Owner","occupation":"TEACHER","representative":true},{"participantId":2,"name":"Companion","occupation":"STUDENT","representative":false},{"participantId":3,"name":"Cancelled","occupation":"TEACHER","representative":false}]}"""
                                }
                            }

                            path.endsWith("standard-programs/1") -> {
                                if (missingPolicy) responseStatus = 404
                                """{"expoId":"$expo","programType":"STANDARD","id":1,"groupId":"$expo","revision":1,"policy":{"formId":"$expo","allowedOccupations":["TEACHER"],"capacity":5,"waitingCapacity":0,"applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"2100-01-01T00:00:00Z","cancelEndedAt":"2100-01-01T00:00:00Z","promotionEndedAt":"2100-01-01T00:00:00Z","closed":false}}"""
                            }

                            path.endsWith("training-programs/batch") -> {
                                """[{"expoId":"$expo","programType":"TRAINING","id":1,"groupId":"$expo","revision":1,"policy":{"formId":"$expo","allowedOccupations":["TEACHER","STUDENT"],"capacity":5,"waitingCapacity":0,"applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"2100-01-01T00:00:00Z","cancelEndedAt":"2100-01-01T00:00:00Z","promotionEndedAt":"2100-01-01T00:00:00Z","closed":false}}]"""
                            }

                            path.endsWith("managed/select") -> {
                                val id =
                                    ObjectMapper()
                                        .readTree(body)
                                        .path("participantIds")
                                        .get(0)
                                        .asLong()
                                val occupation = if (id == 2L) "STUDENT" else "TEACHER"
                                """[{"participantId":$id,"name":"Person","occupation":"$occupation","representative":true}]"""
                            }

                            path.contains("forms/by-id") -> {
                                """{"id":"$expo","expoId":"$expo","purpose":"PROGRAM","version":1,"startDate":"2020-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":[{"id":7,"title":"Training ID","formType":"SENTENCE","requiredStatus":false,"jsonData":{},"otherJson":null,"dynamicFormType":"TRAINING_ID"}]}"""
                            }

                            path.endsWith("resolve-or-create-by-participant") -> {
                                linkBodies.add(body)
                                val id = ObjectMapper().readTree(body).path("participantId").asLong() + 500
                                """{"traineeId":$id,"created":false}"""
                            }

                            path.endsWith("admin-access") -> {
                                assertEquals("Bearer admin", exchange.requestHeaders.getFirst("Authorization"))
                                responseStatus = adminStatus
                                """{"expoId":"$expo","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}"""
                            }

                            path.endsWith("qr") -> {
                                qrCalls.incrementAndGet()
                                assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                                assertEquals("""{"representativeId":1}""", body)
                                duringQr()
                                responseStatus = supplierStatus
                                """{"participantId":${path.split('/')[5]},"code":"private-code"}"""
                            }

                            else -> {
                                responseStatus = 404
                                "{}"
                            }
                        }
                    val scheduledResponse =
                        if (path.endsWith("standard-programs/1") || path.endsWith("training-programs/batch")) {
                            val hour = if (path.endsWith("standard-programs/1")) "09" else "12"
                            val end = if (hour == "09") "10" else "13"
                            response.replace(
                                "\"groupId\":",
                                "\"startedAt\":\"2030-01-01T$hour:00:00+09:00\",\"endedAt\":\"2030-01-01T$end:00:00+09:00\",\"groupId\":",
                            )
                        } else {
                            response
                        }
                    val bytes =
                        (
                            if (pendingPolicy) {
                                scheduledResponse.replace(
                                    "\"revision\":1",
                                    "\"revision\":1,\"pendingRevision\":2",
                                )
                            } else {
                                scheduledResponse
                            }
                        ).toByteArray()
                    exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            for (service in listOf("expo", "user", "form")) {
                registry.add("application.registration.$service-url") { "http://127.0.0.1:${server.address.port}" }
            }
            registry.add("application.attendance.url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.attendance.internal-token") { "attendance-token" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
            registry.add("application.program-sessions.reconcile-delay") { 3600000 }
        }
    }
}
