package team.startup.application

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class AdminGroupMessageHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    private val expo = UUID.fromString("0190abcd-0000-7000-8000-000000000001")
    private val group = UUID.fromString("0190abcd-0000-7000-8000-000000000002")
    private val standard = UUID.fromString("0190abcd-0000-7000-8000-000000000011")
    private val companion = UUID.fromString("0190abcd-0000-7000-8000-000000000012")
    private val samePhone = UUID.fromString("0190abcd-0000-7000-8000-000000000013")
    private val missingPhone = UUID.fromString("0190abcd-0000-7000-8000-000000000014")
    private val training = UUID.fromString("0190abcd-0000-7000-8000-000000000015")
    private val base get() = "/application/expos/$expo/admin-group-messages"

    @BeforeEach
    fun seed() {
        expoStatus.set(200)
        userStatus.set(200)
        notificationStatus.set(200)
        slowNotification = false
        calls.clear()
        requests.clear()
        jdbc.execute("TRUNCATE tb_program_session_application CASCADE")
        listOf(
            Triple(standard, 1L, "STANDARD"),
            Triple(companion, 2L, "STANDARD"),
            Triple(samePhone, 3L, "STANDARD"),
            Triple(missingPhone, 4L, "STANDARD"),
            Triple(training, 5L, "TRAINING"),
        ).forEachIndexed { index, (id, participantId, type) -> insert(id, participantId, type, index + 1L) }
    }

    @Test
    fun `every public operation requires live global admin access and forwards only the bearer token`() {
        assertEquals(
            401,
            mvc
                .perform(get("$base/config"))
                .andReturn()
                .response.status,
        )
        assertEquals(0, calls.count { it.startsWith("notification") })
        expoStatus.set(403)
        assertEquals(
            403,
            mvc
                .perform(get("$base/config").header("Authorization", "Bearer admin"))
                .andReturn()
                .response.status,
        )
        expoStatus.set(200)
        val response =
            mvc
                .perform(get("$base/config").header("Authorization", "Bearer admin").header("X-User-Id", "999"))
                .andReturn()
                .response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(calls.contains("expo:Bearer admin:expo-token"))
        assertFalse(calls.any { it.contains("999") })
    }

    @Test
    fun `expo admin authentication errors pass through without calling downstream providers`() {
        listOf(401, 403).forEach { status ->
            expoStatus.set(status)
            val response = preview(selected(standard), "공통")
            assertEquals(status, response.status, response.contentAsString)
        }
        assertFalse(calls.contains("user"))
        assertFalse(calls.contains("notification-preview"))
    }

    @Test
    fun `user internal authentication and missing participant errors become bad gateway`() {
        listOf(401, 403, 404).forEach { status ->
            userStatus.set(status)
            val response = preview(selected(standard), "공통")
            assertEquals(502, response.status, "User $status: ${response.contentAsString}")
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
        assertFalse(calls.contains("notification-preview"))
    }

    @Test
    fun `notification internal authentication errors become bad gateway and contract errors pass through`() {
        listOf(401 to 502, 403 to 502, 409 to 409, 410 to 410, 422 to 422).forEach { (supplier, expected) ->
            notificationStatus.set(supplier)
            val response = mvc.perform(get("$base/config").header("Authorization", "Bearer admin")).andReturn().response
            assertEquals(expected, response.status, "Notification $supplier: ${response.contentAsString}")
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
    }

    @Test
    fun `personalized selected preview preserves every standard and training application on one representative phone`() {
        val response = preview(selected(standard, companion, training), "{이름}|{프로그램}|{일시}|{상태}")
        assertEquals(201, response.status, response.contentAsString)
        val outbound = requests.last()
        val targets = outbound["targets"]
        assertEquals(3, outbound["applicationCount"].asInt())
        assertEquals(3, targets.size())
        val targetItems = (0 until targets.size()).map(targets::get)
        assertTrue(targetItems.all { it.has("recipientPhone") }, outbound.toString())
        assertEquals(listOf("01012345678", "01012345678", "01012345678"), targetItems.map { it["recipientPhone"].stringValue() })
        assertEquals(listOf("대표", "동행", "연수"), targetItems.map { it["content"].stringValue().substringBefore('|') })
        assertEquals(listOf("STANDARD", "STANDARD", "TRAINING"), targetItems.map { it["programType"].stringValue() })
        assertTrue(targetItems.none { it.has("missingReason") || it.has("templateVariables") })
    }

    @Test
    fun `common filtered preview reads all matching rows deduplicates normalized representative phones and keeps missing targets`() {
        val response = preview(filtered("CONFIRMED"), "공통 안내")
        assertEquals(201, response.status, response.contentAsString)
        val targets = requests.last()["targets"]
        val targetItems = (0 until targets.size()).map(targets::get)
        assertEquals(2, targets.size())
        assertEquals(1, targetItems.count { it["recipientPhone"]?.stringValue() == "01012345678" })
        val missing = targetItems.single { it["missingReason"]?.stringValue() == "REPRESENTATIVE_PHONE_MISSING" }
        assertFalse(missing.has("recipientPhone"))
        assertFalse(missing.has("content"))
        val responseBody = mapper.readTree(response.contentAsString)
        assertEquals(5, responseBody["applicationCount"].asInt())
        assertEquals(1, responseBody["recipientCount"].asInt())
        assertEquals(1, responseBody["messageCount"].asInt())
        assertEquals(2, responseBody["targetCount"].asInt())
        assertEquals(1, responseBody["missingCount"].asInt())
    }

    @Test
    fun `kakao cannot be previewed before an approved template provider is configured`() {
        val requestId = UUID.randomUUID()
        val response =
            mvc
                .perform(
                    post("$base/previews")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                mapOf(
                                    "requestId" to requestId,
                                    "source" to
                                        mapOf(
                                            "scope" to mapper.readTree(selected(standard)),
                                            "channel" to "KAKAO",
                                            "templateId" to "not-configured",
                                            "fallbackToSms" to false,
                                        ),
                                ),
                            ),
                        ),
                ).andReturn()
                .response

        assertEquals(422, response.status, response.contentAsString)
        assertTrue(calls.contains("notification-config"))
        assertTrue((0 until requests.size).map(requests::get).none { it["requestId"]?.stringValue() == requestId.toString() })
    }

    @Test
    fun `execution rechecks authority and rejects changed target before creating a job`() {
        val scope = selected(standard, companion)
        val preview = mapper.readTree(preview(scope, "공통").contentAsString)
        assertEquals(1, preview["targetCount"].asInt())
        val execution = execution(preview["targetFingerprint"].stringValue(), scope, "공통")
        jdbc.update("UPDATE tb_program_session_application SET status='CANCELLED' WHERE id=?", companion)
        val changed =
            mvc
                .perform(
                    post("$base/previews/${preview["previewId"].stringValue()}/jobs")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution),
                ).andReturn()
                .response
        assertEquals(409, changed.status)
        assertEquals(0, calls.count { it == "notification-job" })
        expoStatus.set(403)
        assertEquals(
            403,
            mvc
                .perform(
                    post("$base/previews/${preview["previewId"].stringValue()}/jobs")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution),
                ).andReturn()
                .response.status,
        )
        assertEquals(0, calls.count { it == "notification-job" })
    }

    @Test
    fun `job execution partial failure status and confirmed failure retry are proxied with reauthorization`() {
        val preview = mapper.readTree(preview(selected(standard), "공통").contentAsString)
        val execution = execution(preview["targetFingerprint"].stringValue(), selected(standard), "공통")
        val created =
            mvc
                .perform(
                    post("$base/previews/${preview["previewId"].stringValue()}/jobs")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution),
                ).andReturn()
                .response
        assertEquals(202, created.status, created.contentAsString)
        val jobId = mapper.readTree(created.contentAsString)["jobId"].stringValue()
        val status = mvc.perform(get("$base/jobs/$jobId").header("Authorization", "Bearer admin")).andReturn().response
        assertEquals(200, status.status)
        assertEquals("COMPLETED_WITH_FAILURES", mapper.readTree(status.contentAsString)["status"].stringValue())
        val retry =
            mvc
                .perform(
                    post("$base/jobs/$jobId/retries")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution.replace(Regex("\"idempotencyKey\":\"[^\"]+\""), "\"idempotencyKey\":\"${UUID.randomUUID()}\"")),
                ).andReturn()
                .response
        assertEquals(202, retry.status, retry.contentAsString)
        assertEquals(4, calls.count { it.startsWith("expo:") })
        assertTrue(calls.containsAll(listOf("notification-job", "notification-status", "notification-retry")))
    }

    @Test
    fun `malformed partial participant response and notification timeout never become partial success`() {
        userStatus.set(206)
        val partial = preview(selected(standard, companion), "공통")
        assertEquals(502, partial.status, partial.contentAsString)
        assertEquals(0, calls.count { it == "notification-preview" })
        userStatus.set(200)
        slowNotification = true
        val timeout = preview(selected(standard), "공통")
        assertEquals(504, timeout.status, timeout.contentAsString)
        slowNotification = false
    }

    private fun insert(
        id: UUID,
        participantId: Long,
        type: String,
        programId: Long,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id,expo_id,program_type,program_id,group_id,participant_id,trainee_id,status,definition_snapshot," +
                "form_id,form_version,questions_snapshot,answers_snapshot,created_at) VALUES (?,?,?,?,?,?,?," +
                "'CONFIRMED',CAST(? AS jsonb),?,1,'[]','{}',?)",
            id,
            expo,
            type,
            programId,
            group,
            participantId,
            if (type == "TRAINING") 50L else null,
            """{"title":"프로그램$programId","startedAt":"2026-10-12 09:00","endedAt":"2026-10-12 10:00","place":"강의실"}""",
            UUID.randomUUID(),
            Timestamp.from(Instant.parse("2026-10-11T00:00:0${programId}Z")),
        )
    }

    private fun selected(vararg ids: UUID): String = """{"kind":"SELECTED","applicationIds":${mapper.writeValueAsString(ids.toList())}}"""

    private fun filtered(status: String): String = """{"kind":"FILTERED","filter":{"status":"$status","sort":"CREATED_ASC"}}"""

    private fun preview(
        scope: String,
        content: String,
    ): MockHttpServletResponse =
        mvc
            .perform(
                post("$base/previews")
                    .header("Authorization", "Bearer admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            mapOf(
                                "requestId" to UUID.randomUUID(),
                                "source" to
                                    mapOf(
                                        "scope" to mapper.readTree(scope),
                                        "channel" to "SMS",
                                        "body" to content,
                                        "fallbackToSms" to false,
                                    ),
                            ),
                        ),
                    ),
            ).andReturn()
            .response

    private fun execution(
        fingerprint: String,
        scope: String,
        content: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "source" to
                    mapOf(
                        "scope" to mapper.readTree(scope),
                        "channel" to "SMS",
                        "body" to content,
                        "fallbackToSms" to false,
                    ),
                "targetFingerprint" to fingerprint,
                "idempotencyKey" to UUID.randomUUID(),
            ),
        )

    companion object {
        private val expoStatus = AtomicInteger(200)
        private val userStatus = AtomicInteger(200)
        private val notificationStatus = AtomicInteger(200)
        private val calls = CopyOnWriteArrayList<String>()
        private val requests = CopyOnWriteArrayList<JsonNode>()

        @Volatile private var slowNotification = false
        private const val JOB_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private val previewId = UUID.fromString("0190abcd-0000-7000-8000-000000000099")
        private val mapper = ObjectMapper()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expo/") { exchange ->
                    calls.add(
                        "expo:${exchange.requestHeaders.getFirst("Authorization")}:" +
                            exchange.requestHeaders.getFirst("X-Internal-Token"),
                    )
                    respond(
                        exchange,
                        expoStatus.get(),
                        """
                        {
                            "expoId":"0190abcd-0000-7000-8000-000000000001","adminId":7,
                            "permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"
                        }
                        """.trimIndent(),
                    )
                }
                createContext("/internal/standard-participants/details") { exchange ->
                    calls.add("user")
                    if (userStatus.get() in 400..599) {
                        respond(exchange, userStatus.get(), "{}")
                        return@createContext
                    }
                    val body = mapper.readTree(exchange.requestBody.readAllBytes())
                    val idNodes = body["participantIds"]
                    val ids = (0 until idNodes.size()).map { idNodes[it].asLong() }
                    val participants =
                        ids.take(if (userStatus.get() == 206) 1 else ids.size).map { id ->
                            when (id) {
                                1L -> participant(1, "대표", "010-1234-5678", null)
                                2L -> participant(2, "동행", "01099999999", 1)
                                3L -> participant(3, "동일", "01012345678", null)
                                4L -> mapOf("participantId" to 4, "name" to "누락", "phoneNumber" to null, "representativeId" to null)
                                5L -> participant(5, "연수", "01055555555", 1)
                                else -> error("unexpected participant")
                            }
                        }
                    respond(exchange, 200, mapper.writeValueAsString(participants))
                }
                createContext("/internal/admin-group-messages") { exchange ->
                    if (slowNotification) Thread.sleep(10_500)
                    val path = exchange.requestURI.path
                    when {
                        path.endsWith("/config") -> {
                            calls.add("notification-config")
                            respond(
                                exchange,
                                notificationStatus.get(),
                                """
                                {
                                    "enabled":true,
                                    "channels":{"SMS":{"enabled":true,"templates":[]},"KAKAO":{"enabled":false,"templates":[]}},
                                    "smsFallbackDefault":false
                                }
                                """.trimIndent(),
                            )
                        }

                        path.endsWith("/previews") -> {
                            calls.add("notification-preview")
                            val request = mapper.readTree(exchange.requestBody.readAllBytes()).also(requests::add)
                            val items = request["targets"]
                            val missing = items.count { it.has("missingReason") }
                            val recipientCount =
                                (0 until items.size())
                                    .map(items::get)
                                    .mapNotNull { it["recipientPhone"]?.stringValue() }
                                    .distinct()
                                    .size
                            respond(
                                exchange,
                                201,
                                mapper.writeValueAsString(
                                    mapOf(
                                        "previewId" to previewId,
                                        "targetFingerprint" to request["targetFingerprint"].stringValue(),
                                        "applicationCount" to request["applicationCount"].asInt(),
                                        "recipientCount" to recipientCount,
                                        "messageCount" to items.size() - missing,
                                        "targetCount" to items.size(),
                                        "sendableCount" to items.size() - missing,
                                        "missingCount" to missing,
                                        "expiresAt" to "2026-10-13T00:00:00Z",
                                        "items" to items,
                                    ),
                                ),
                            )
                        }

                        path.endsWith("/jobs") -> {
                            calls.add("notification-job")
                            respond(exchange, 202, job())
                        }

                        path.endsWith("/retries") -> {
                            calls.add("notification-retry")
                            respond(exchange, 202, job())
                        }

                        else -> {
                            calls.add("notification-status")
                            respond(exchange, 200, job())
                        }
                    }
                }
                start()
            }

        private fun participant(
            id: Long,
            name: String,
            phone: String?,
            representativeId: Long?,
        ) = mapOf("participantId" to id, "name" to name, "phoneNumber" to phone, "representativeId" to representativeId)

        private fun job() =
            """
            {
                "jobId":"$JOB_ID","previewId":"$previewId","status":"COMPLETED_WITH_FAILURES","createdAt":"2026-10-12T00:00:00Z",
                "counts":{"total":1,"pending":0,"sending":0,"accepted":0,"delivered":0,"failed":1,"unknown":0,"missing":0},
                "items":[{"targetId":"target","applicationId":"0190abcd-0000-7000-8000-000000000011","participantId":1,
                "recipientMasked":"010-****-5678","status":"FAILED","providerCode":"TEST","failureReason":"fake"}]
            }
            """.trimIndent()

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val url = "http://127.0.0.1:${server.address.port}"
            registry.add("application.admin-group-messaging.expo-url") { url }
            registry.add("application.admin-group-messaging.user-url") { url }
            registry.add("application.admin-group-messaging.notification-url") { url }
            registry.add("application.admin-group-messaging.expo-internal-token") { "expo-token" }
            registry.add("application.admin-group-messaging.user-internal-token") { "user-token" }
            registry.add("application.admin-group-messaging.notification-internal-token") { "notification-token" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
