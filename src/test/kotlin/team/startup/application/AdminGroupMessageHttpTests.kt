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
import java.util.concurrent.CopyOnWriteArraySet
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
        kakaoEnabled = false
        preregistrationEnabled = true
        representativePhone = "010-1234-5678"
        companionName = "동행"
        expoTitle = "광주 박람회"
        calls.clear()
        targetTokens.clear()
        requests.clear()
        jdbc.execute("TRUNCATE tb_program_session_application CASCADE")
        jdbc.execute("TRUNCATE tb_preregister_application, tb_preregister_reservation, tb_preregister_session_state CASCADE")
        listOf(
            Triple(standard, 1L, "STANDARD"),
            Triple(companion, 2L, "STANDARD"),
            Triple(samePhone, 3L, "STANDARD"),
            Triple(missingPhone, 4L, "STANDARD"),
            Triple(training, 5L, "TRAINING"),
        ).forEachIndexed { index, (id, participantId, type) -> insert(id, participantId, type, index + 1L) }
    }

    private fun preregistrationScope(vararg keys: String) = """{"kind":"SELECTED","keys":${mapper.writeValueAsString(keys.toList())}}"""

    private fun seedPreregistrations(): List<String> {
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, ?)",
            expo,
            """{"id":1,"expoId":"$expo","title":"오전 회차","startedAt":"2026-10-31T00:30:00Z","endedAt":"2026-10-31T03:30:00Z","place":"접수실","capacity":100,"closed":false,"revision":1}""",
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 1, 2, 'CONFIRMED')",
            expo,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "VALUES (?, 'waiting', 1, '{}', ?, 2, 'WAITING', 1), (?, 'pending', 1, '{}', ?, 1, 'RESERVED', NULL)",
            expo,
            """{"name":"예약 대표","phoneNumber":"010-1234-5678"}""",
            expo,
            """{"name":"복구 대표","phoneNumber":"01077778888"}""",
        )
        val id = jdbc.queryForObject("SELECT id FROM tb_preregister_application WHERE expo_id = ?", Long::class.java, expo)
        return listOf("APPLICATION:$expo:$id", "RESERVATION:$expo:waiting", "RESERVATION:$expo:pending")
    }

    @Test
    fun `ledger advertises messaging only when notification declares end to end target support`() {
        seedPreregistrations()

        fun targets() =
            mvc
                .perform(
                    post("/application/admin/expos/$expo/preregistrations/targets")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"kind":"FILTERED","filter":{"status":"WAITING"}}"""),
                ).andReturn()
                .response
        val supported = targets()
        assertEquals(200, supported.status, supported.contentAsString)
        assertTrue(mapper.readTree(supported.contentAsString)["messagingSupported"].asBoolean())
        preregistrationEnabled = false
        assertFalse(mapper.readTree(targets().contentAsString)["messagingSupported"].asBoolean())
        notificationStatus.set(503)
        val unavailable = targets()
        assertEquals(200, unavailable.status)
        assertFalse(mapper.readTree(unavailable.contentAsString)["messagingSupported"].asBoolean())
    }

    @Test
    fun `preregistration current representative and reservation snapshot deduplicate without fabricated IDs`() {
        val keys = seedPreregistrations()
        val common = preview(preregistrationScope(*keys.toTypedArray()), "공통")
        assertEquals(201, common.status, common.contentAsString)
        val result = mapper.readTree(common.contentAsString)
        assertEquals(3, result["applicationCount"].asInt())
        assertEquals(2, result["messageCount"].asInt())
        val personalized = preview(preregistrationScope(*keys.toTypedArray()), "{이름} {프로그램} {일시} {장소} {상태}")
        assertEquals(201, personalized.status, personalized.contentAsString)
        val targets = requests.last()["targets"]
        assertEquals(3, targets.size())
        assertTrue(targets.all { !it.has("applicationId") && !it.has("programType") && it["targetId"] == it["ledgerKey"] })
        val application = targets.single { it["ledgerKey"].stringValue().startsWith("APPLICATION:") }
        assertEquals("01012345678", application["recipientPhone"].stringValue())
        assertTrue(application["content"].stringValue().contains("동행 사전등록 · 오전 회차 10.31(토) 09:30~12:30 접수실 확정"))
        val waiting = targets.single { it["ledgerKey"].stringValue().endsWith(":waiting") }
        assertFalse(waiting.has("participantId"))
        assertTrue(waiting["content"].stringValue().startsWith("예약 대표"))
        assertEquals(setOf("user:user-token", "notification:notification-token"), targetTokens.toSet())
    }

    @Test
    fun `preregistration refuses mixed selectors foreign keys invalid filters and old notification contract`() {
        val keys = seedPreregistrations()
        listOf(
            """{"kind":"SELECTED","keys":["${keys.first()}"],"applicationIds":["$standard"]}""",
            """{"kind":"FILTERED","filter":{},"preregistrationFilter":{}}""",
            """{"kind":"FILTERED","preregistrationFilter":{"programType":"STANDARD"}}""",
            """{"kind":"FILTERED","preregistrationFilter":{"q":" "}}""",
            """{"kind":"FILTERED","preregistrationFilter":{"page":1}}""",
            preregistrationScope(keys.first(), keys.first()),
        ).forEach { assertEquals(400, preview(it, "공통").status, it) }
        listOf(standard.toString(), "APPLICATION:${UUID.randomUUID()}:1", "RESERVATION:$expo:missing").forEach {
            assertEquals(404, preview(preregistrationScope(it), "공통").status)
        }
        preregistrationEnabled = false
        assertEquals(503, preview(preregistrationScope(keys.first()), "공통").status)
        assertFalse(calls.contains("notification-preview"))
    }

    @Test
    fun `preregistration filters preserve reservation search semantics missing definitions and QR stay missing`() {
        seedPreregistrations()
        val scope = """{"kind":"FILTERED","preregistrationFilter":{"sessionId":1,"status":["WAITING","PENDING"],"q":"예약"}}"""
        val response = preview(scope, "{이름} {상태}")
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(1, mapper.readTree(response.contentAsString)["applicationCount"].asInt())
        assertFalse(calls.contains("user"))
        assertEquals(201, preview(scope, "{URL}").status)
        assertEquals("TEMPLATE_DATA_MISSING", requests.last()["targets"][0]["missingReason"].stringValue())
        jdbc.update("DELETE FROM tb_preregister_application WHERE expo_id = ?", expo)
        jdbc.update("DELETE FROM tb_preregister_session_state WHERE expo_id = ?", expo)
        assertEquals(201, preview(scope, "{프로그램}").status)
        assertEquals("TEMPLATE_DATA_MISSING", requests.last()["targets"][0]["missingReason"].stringValue())
    }

    @Test
    fun `preregistration kakao preserves cancellation pending labels and missing stored values`() {
        val keys = seedPreregistrations()
        kakaoEnabled = true
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE expo_id = ?", expo)
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = 'REJECTED', withdrawn_at = CURRENT_TIMESTAMP WHERE request_id = 'waiting'",
        )
        val response = kakaoPreview(preregistrationScope(*keys.toTypedArray()), false)
        assertEquals(201, response.status, response.contentAsString)
        val targets = requests.last()["targets"]
        assertEquals(2, targets.count { it["templateVariables"]["status"].stringValue() == "취소" })
        assertEquals(1, targets.count { it["templateVariables"]["status"].stringValue() == "처리 중" })
        jdbc.update("UPDATE tb_preregister_session_state SET definition = '{}' WHERE expo_id = ?", expo)
        assertEquals(201, kakaoPreview(preregistrationScope(keys[2]), false).status)
        assertEquals("TEMPLATE_DATA_MISSING", requests.last()["targets"][0]["missingReason"].stringValue())
    }

    @Test
    fun `preregistration send retry recheck status phone and removed keys before notification`() {
        val keys = seedPreregistrations()
        val scope = preregistrationScope(keys[1])

        fun fingerprint() = mapper.readTree(preview(scope, "공통").contentAsString)["targetFingerprint"].stringValue()

        fun run(
            path: String,
            fingerprint: String,
        ) = mvc
            .perform(
                post(path)
                    .header("Authorization", "Bearer admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(execution(fingerprint, scope, "공통")),
            ).andReturn()
            .response
        val first = fingerprint()
        val started = run("$base/previews/$previewId/jobs", first)
        assertEquals(202, started.status)
        assertEquals(keys[1], mapper.readTree(started.contentAsString)["items"][0]["ledgerKey"].stringValue())
        val retried = run("$base/jobs/$JOB_ID/retries", first)
        assertEquals(202, retried.status)
        assertTrue(mapper.readTree(retried.contentAsString)["items"][0]["applicationId"].isNull)
        jdbc.update(
            "UPDATE tb_preregister_reservation SET user_request = ? WHERE request_id = 'waiting'",
            """{"name":"예약 대표","phoneNumber":"01099998888"}""",
        )
        assertEquals(409, run("$base/previews/$previewId/jobs", first).status)
        assertEquals(409, run("$base/jobs/$JOB_ID/retries", first).status)
        val second = fingerprint()
        jdbc.update("UPDATE tb_preregister_reservation SET state = 'RESERVED' WHERE request_id = 'waiting'")
        assertEquals(409, run("$base/jobs/$JOB_ID/retries", second).status)
        val third = fingerprint()
        jdbc.update("UPDATE tb_preregister_reservation SET state = 'COMPLETED' WHERE request_id = 'waiting'")
        assertEquals(409, run("$base/previews/$previewId/jobs", third).status)
        assertEquals(1, calls.count { it == "notification-job" })
        assertEquals(1, calls.count { it == "notification-retry" })
    }

    @Test
    fun `preregistration filtered targets are capped at five thousand ledger rows`() {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "SELECT ?, 'bulk-' || n, 1, '{}', '{}', 1, 'WAITING', n FROM generate_series(1, 5001) n",
            expo,
        )
        val response = preview("""{"kind":"FILTERED","preregistrationFilter":{"status":"WAITING"}}""", "공통")
        assertEquals(422, response.status, response.contentAsString)
        assertFalse(calls.contains("notification-preview"))
        jdbc.update("DELETE FROM tb_preregister_reservation WHERE request_id = 'bulk-5001'")
        val atLimit = preview("""{"kind":"FILTERED","preregistrationFilter":{"status":"WAITING"}}""", "공통")
        assertEquals(201, atLimit.status, atLimit.contentAsString)
        assertEquals(5000, mapper.readTree(atLimit.contentAsString)["applicationCount"].asInt())
    }

    @Test
    fun `preregistration application search uses messaging token and live name phone and definition invalidate all rows`() {
        val keys = seedPreregistrations()
        val scope = """{"kind":"FILTERED","preregistrationFilter":{"q":"동행","status":"CONFIRMED"}}"""
        val response = preview(scope, "공통")
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(1, mapper.readTree(response.contentAsString)["applicationCount"].asInt())
        assertTrue(calls.contains("user-search:user-token"))
        val selected = preregistrationScope(keys[0], keys[1])

        fun currentFingerprint() = mapper.readTree(preview(selected, "공통").contentAsString)["targetFingerprint"].stringValue()

        fun checkCurrentChange(fingerprint: String) {
            val changed =
                mvc
                    .perform(
                        post("$base/previews/$previewId/jobs")
                            .header("Authorization", "Bearer admin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(execution(fingerprint, selected, "공통")),
                    ).andReturn()
                    .response
            assertEquals(409, changed.status)
        }
        val beforePhone = currentFingerprint()
        representativePhone = "01088887777"
        checkCurrentChange(beforePhone)
        val beforeName = currentFingerprint()
        companionName = "변경된 이름"
        checkCurrentChange(beforeName)
        representativePhone = "010-1234-5678"
        val fingerprint = mapper.readTree(preview(selected, "공통").contentAsString)["targetFingerprint"].stringValue()
        // 공통 문구 중복 제거로 빠진 예약의 상태 변경도 실행을 막아야 한다.
        jdbc.update("UPDATE tb_preregister_reservation SET state = 'RESERVED' WHERE request_id = 'waiting'")
        val changed =
            mvc
                .perform(
                    post("$base/previews/$previewId/jobs")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution(fingerprint, selected, "공통")),
                ).andReturn()
                .response
        assertEquals(409, changed.status)
        val next = mapper.readTree(preview(selected, "공통").contentAsString)["targetFingerprint"].stringValue()
        jdbc.update("UPDATE tb_preregister_session_state SET definition = replace(definition, '접수실', '변경 장소') WHERE expo_id = ?", expo)
        val definitionChanged =
            mvc
                .perform(
                    post("$base/jobs/$JOB_ID/retries")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution(next, selected, "공통")),
                ).andReturn()
                .response
        assertEquals(409, definitionChanged.status)
        expoStatus.set(403)
        assertEquals(403, preview(selected, "공통").status)
        assertFalse(calls.contains("notification-job"))
        assertFalse(calls.contains("notification-retry"))
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
    fun `each provider receives its own internal token instead of the application token`() {
        val response = preview(selected(standard), "공통")
        assertEquals(201, response.status, response.contentAsString)
        assertTrue(calls.contains("expo:Bearer admin:expo-token"), calls.toString())
        assertEquals(setOf("user:user-token", "notification:notification-token"), targetTokens.toSet())
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
    fun `filtered scope uses ledger status and event date filters and rejects person filters it cannot apply`() {
        val all = mapper.readTree(preview(filtered("CONFIRMED"), "공통 안내").contentAsString)["applicationCount"].asInt()
        val scoped = """{"kind":"FILTERED","filter":{"status":["CONFIRMED","WAITING"],"eventDate":"2026-10-12","sort":"CREATED_ASC"}}"""
        val response = preview(scoped, "공통 안내")
        assertEquals(201, response.status, response.contentAsString)
        assertEquals(all, mapper.readTree(response.contentAsString)["applicationCount"].asInt())
        val otherDay = preview("""{"kind":"FILTERED","filter":{"eventDate":"2026-10-13"}}""", "공통 안내")
        assertEquals(201, otherDay.status, otherDay.contentAsString)
        assertEquals(0, mapper.readTree(otherDay.contentAsString)["applicationCount"].asInt())
        val sent = requests.size
        listOf(""""q":"홍길동"""", """"occupation":"TEACHER"""").forEach {
            val rejected = preview("""{"kind":"FILTERED","filter":{$it}}""", "공통 안내")
            assertEquals(400, rejected.status, it)
        }
        assertEquals(sent, requests.size)
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
    fun `kakao template receives expo name status and KST session time and forwards sms fallback`() {
        kakaoEnabled = true
        val response = kakaoPreview(selected(standard), fallbackToSms = true)
        assertEquals(201, response.status, response.contentAsString)
        val outbound = requests.last()
        assertTrue(outbound["fallbackToSms"].asBoolean(), outbound.toString())
        assertEquals("K6", outbound["templateId"].stringValue())
        val target = outbound["targets"][0]
        val variables = target["templateVariables"]
        assertEquals("광주 박람회", variables["expoName"].stringValue())
        assertEquals("확정", variables["status"].stringValue())
        assertEquals("10.12(월) 09:00~10:00", variables["sessionName"].stringValue())
        assertEquals("[광주 박람회] 대표님 프로그램1 10.12(월) 09:00~10:00 확정", target["content"].stringValue())
        assertEquals(1, calls.count { it == "expo-title" })

        val sms = preview(selected(standard), "{일시}")
        assertEquals(201, sms.status, sms.contentAsString)
        assertEquals("10.12(월) 09:00~10:00", requests.last()["targets"][0]["content"].stringValue())
        assertFalse(requests.last()["fallbackToSms"].asBoolean())
        assertEquals(1, calls.count { it == "expo-title" })
    }

    @Test
    fun `sms application summary renders per application and links stay missing until a qr code contract exists`() {
        val response = preview(selected(standard, training), "{이름}님\n{신청내역}")
        assertEquals(201, response.status, response.contentAsString)
        val targets = requests.last()["targets"]
        assertEquals(
            "대표님\n- 프로그램: 프로그램1\n- 일시: 10.12(월) 09:00~10:00\n- 신청 상태: 확정",
            targets[0]["content"].stringValue(),
        )
        assertTrue(targets[1]["content"].stringValue().startsWith("연수님\n- 프로그램: 프로그램"), targets.toString())

        val link = preview(selected(standard), "QR {URL}")
        assertEquals(201, link.status, link.contentAsString)
        assertEquals("TEMPLATE_DATA_MISSING", requests.last()["targets"][0]["missingReason"].stringValue())
    }

    @Test
    fun `kakao application summary is sent only when mapped and fixed variables pass through to the client`() {
        kakaoEnabled = true
        val config =
            mapper.readTree(
                mvc
                    .perform(get("$base/config").header("Authorization", "Bearer admin"))
                    .andReturn()
                    .response.contentAsString,
            )
        val templates = config["channels"]["KAKAO"]["templates"]
        assertEquals("https://glink.kr/busy", templates[1]["fixedVariables"]["#{주차장URL}"].stringValue())
        assertFalse(templates[0].has("fixedVariables"), templates.toString())

        val summary = previewSource(selected(standard), mapOf("channel" to "KAKAO", "templateId" to "K7", "fallbackToSms" to false))
        assertEquals(201, summary.status, summary.contentAsString)
        val target = requests.last()["targets"][0]
        assertEquals(
            "- 프로그램: 프로그램1\n- 일시: 10.12(월) 09:00~10:00\n- 신청 상태: 확정",
            target["templateVariables"]["applicationSummary"].stringValue(),
        )
        assertEquals(
            "대표님\n${target["templateVariables"]["applicationSummary"].stringValue()}\n주차 https://glink.kr/busy",
            target["content"].stringValue(),
        )

        // Notification처럼 한 번에 치환하므로 신청 값 안의 placeholder 모양 글자는 고정값으로 바뀌지 않는다.
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = " +
                "jsonb_set(definition_snapshot, '{title}', to_jsonb(CAST(? AS text))) WHERE id = ?",
            "강연 #{주차장URL}",
            standard,
        )
        assertEquals(
            201,
            previewSource(selected(standard), mapOf("channel" to "KAKAO", "templateId" to "K7", "fallbackToSms" to false)).status,
        )
        val literal = requests.last()["targets"][0]["content"].stringValue()
        assertTrue(literal.startsWith("대표님\n- 프로그램: 강연 #{주차장URL}\n"), literal)
        assertTrue(literal.endsWith("주차 https://glink.kr/busy"), literal)

        assertEquals(201, kakaoPreview(selected(standard), fallbackToSms = false).status)
        assertFalse(requests.last()["targets"][0]["templateVariables"].has("applicationSummary"))

        val link = previewSource(selected(standard), mapOf("channel" to "KAKAO", "templateId" to "K8", "fallbackToSms" to false))
        assertEquals(201, link.status, link.contentAsString)
        assertEquals("TEMPLATE_DATA_MISSING", requests.last()["targets"][0]["missingReason"].stringValue())
    }

    @Test
    fun `kakao expo name without a title stays missing and title changes invalidate the fingerprint`() {
        kakaoEnabled = true
        val first = mapper.readTree(kakaoPreview(selected(standard), fallbackToSms = false).contentAsString)
        expoTitle = "다른 박람회"
        val second = mapper.readTree(kakaoPreview(selected(standard), fallbackToSms = false).contentAsString)
        assertFalse(first["targetFingerprint"] == second["targetFingerprint"])
        expoTitle = "   "
        val response = kakaoPreview(selected(standard), fallbackToSms = false)
        assertEquals(201, response.status, response.contentAsString)
        val target = requests.last()["targets"][0]
        assertEquals("TEMPLATE_DATA_MISSING", target["missingReason"].stringValue())
        assertFalse(target.has("templateVariables"))
        assertFalse(target.has("content"))
        assertFalse(target.has("recipientPhone"))
    }

    @Test
    fun `sms fallback is accepted only for kakao`() {
        kakaoEnabled = true
        val sent = requests.size
        val sms = previewSource(selected(standard), mapOf("channel" to "SMS", "body" to "공통 안내", "fallbackToSms" to true))
        assertEquals(400, sms.status, sms.contentAsString)
        assertEquals(sent, requests.size)
        assertEquals(201, kakaoPreview(selected(standard), fallbackToSms = true).status)
        assertEquals(201, kakaoPreview(selected(standard), fallbackToSms = false).status)
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

    @Test
    fun `venue rendering keeps separate application snapshot values for standard and training sessions`() {
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = " +
                "jsonb_set(definition_snapshot, '{place}', to_jsonb(CAST(? AS text))) WHERE id = ?",
            "연수실",
            training,
        )
        val response = preview(selected(standard, training), "{프로그램}|{장소}")
        assertEquals(201, response.status, response.contentAsString)
        val targets = requests.last()["targets"]
        assertEquals(listOf("프로그램1|강의실", "프로그램5|연수실"), (0 until targets.size()).map { targets[it]["content"].stringValue() })
        assertEquals(0, calls.count { it == "notification-job" })
    }

    @Test
    fun `legacy absent null and blank snapshot venues stay missing without a display fallback`() {
        listOf("{}", """{"place":null}""", """{"place":"   "}""").forEach { definition ->
            jdbc.update(
                "UPDATE tb_program_session_application SET definition_snapshot = CAST(? AS jsonb) WHERE id = ?",
                definition,
                standard,
            )
            val response = preview(selected(standard), "{장소}")
            assertEquals(201, response.status, response.contentAsString)
            val target = requests.last()["targets"][0]
            assertEquals("TEMPLATE_DATA_MISSING", target["missingReason"].stringValue())
            assertFalse(target.has("content"))
            assertFalse(target.has("recipientPhone"))
            assertEquals(201, preview(selected(standard), "공통 안내").status)
            assertEquals("공통 안내", requests.last()["targets"][0]["content"].stringValue())
        }
    }

    @Test
    fun `changed snapshot venue invalidates preview fingerprint before any job is sent`() {
        val scope = selected(standard)
        val first = mapper.readTree(preview(scope, "{장소}").contentAsString)
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = " +
                "jsonb_set(definition_snapshot, '{place}', to_jsonb(CAST(? AS text))) WHERE id = ?",
            "변경된 스냅샷",
            standard,
        )
        val second = mapper.readTree(preview(scope, "{장소}").contentAsString)
        assertEquals("변경된 스냅샷", requests.last()["targets"][0]["content"].stringValue())
        assertFalse(first["targetFingerprint"] == second["targetFingerprint"])
        val response =
            mvc
                .perform(
                    post("$base/previews/${first["previewId"].stringValue()}/jobs")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(execution(first["targetFingerprint"].stringValue(), scope, "{장소}")),
                ).andReturn()
                .response
        assertEquals(409, response.status, response.contentAsString)
        assertEquals(0, calls.count { it == "notification-job" })
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

    private fun kakaoPreview(
        scope: String,
        fallbackToSms: Boolean,
    ): MockHttpServletResponse = previewSource(scope, mapOf("channel" to "KAKAO", "templateId" to "K6", "fallbackToSms" to fallbackToSms))

    private fun previewSource(
        scope: String,
        source: Map<String, Any>,
    ): MockHttpServletResponse =
        mvc
            .perform(
                post("$base/previews")
                    .header("Authorization", "Bearer admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            mapOf("requestId" to UUID.randomUUID(), "source" to mapOf("scope" to mapper.readTree(scope)) + source),
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
        private val targetTokens = CopyOnWriteArraySet<String>()

        @Volatile private var slowNotification = false

        @Volatile private var kakaoEnabled = false

        @Volatile private var preregistrationEnabled = true

        @Volatile private var representativePhone = "010-1234-5678"

        @Volatile private var companionName = "동행"

        @Volatile private var expoTitle = "광주 박람회"
        private const val KAKAO_DISABLED = """{"enabled":false,"templates":[]}"""
        private val KAKAO_CHANNEL =
            """
            {"enabled":true,"templates":[{"id":"K6","name":"프로그램 참가 안내",
            "content":"[#{행사명}] #{이름}님 #{프로그램} #{일시} #{상태}",
            "variableMapping":{"expoName":"#{행사명}","name":"#{이름}","programName":"#{프로그램}","sessionName":"#{일시}","status":"#{상태}"},
            "variables":["#{행사명}","#{이름}","#{프로그램}","#{일시}","#{상태}"]},
            {"id":"K7","name":"사전 안내","content":"#{이름}님\n#{신청내역}\n주차 #{주차장URL}",
            "variableMapping":{"name":"#{이름}","applicationSummary":"#{신청내역}"},
            "variables":["name","applicationSummary"],"fixedVariables":{"#{주차장URL}":"https://glink.kr/busy"}},
            {"id":"K8","name":"대기자 안내","content":"#{이름}님 #{URL}",
            "variableMapping":{"name":"#{이름}","applicationUrl":"#{URL}"},"variables":["name","applicationUrl"]}]}
            """.trimIndent()
        private const val JOB_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private val previewId = UUID.fromString("0190abcd-0000-7000-8000-000000000099")
        private val mapper = ObjectMapper()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expo/") { exchange ->
                    if (exchange.requestURI.path == "/internal/expo/0190abcd-0000-7000-8000-000000000001") {
                        calls.add("expo-title")
                        respond(exchange, 200, mapper.writeValueAsString(mapOf("title" to expoTitle, "startedDay" to "2026-10-12")))
                        return@createContext
                    }
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
                    targetTokens.add("user:${exchange.requestHeaders.getFirst("X-Internal-Token")}")
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
                                1L -> participant(1, "대표", representativePhone, null)
                                2L -> participant(2, companionName, "01099999999", 1)
                                3L -> participant(3, "동일", "01012345678", null)
                                4L -> mapOf("participantId" to 4, "name" to "누락", "phoneNumber" to null, "representativeId" to null)
                                5L -> participant(5, "연수", "01055555555", 1)
                                else -> error("unexpected participant")
                            }
                        }
                    respond(exchange, 200, mapper.writeValueAsString(participants))
                }
                createContext("/internal/expos/") { exchange ->
                    calls.add("user-search:${exchange.requestHeaders.getFirst("X-Internal-Token")}")
                    respond(exchange, 200, """{"items":[{"participantId":2}],"nextCursor":null}""")
                }
                createContext("/internal/admin-group-messages") { exchange ->
                    if (slowNotification) Thread.sleep(10_500)
                    targetTokens.add("notification:${exchange.requestHeaders.getFirst("X-Internal-Token")}")
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
                                    "targetContracts":${if (preregistrationEnabled) "[\"PREREGISTRATION_GROUP_MESSAGE_V1\"]" else "[]"},
                                    "channels":{"SMS":{"enabled":true,"templates":[]},"KAKAO":${if (kakaoEnabled) KAKAO_CHANNEL else KAKAO_DISABLED}},
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

        private fun job(): String {
            val targets = requests.lastOrNull()?.path("targets")
            if (targets?.any { it.has("ledgerKey") } == true) {
                return mapper.writeValueAsString(
                    mapOf(
                        "jobId" to JOB_ID,
                        "previewId" to previewId,
                        "status" to "COMPLETED_WITH_FAILURES",
                        "createdAt" to "2026-10-12T00:00:00Z",
                        "counts" to
                            mapOf(
                                "total" to targets.size(),
                                "pending" to 0,
                                "sending" to 0,
                                "accepted" to 0,
                                "delivered" to 0,
                                "failed" to targets.size(),
                                "unknown" to 0,
                                "missing" to 0,
                            ),
                        "items" to
                            (0 until targets.size()).map { index ->
                                mapOf(
                                    "targetId" to targets[index]["targetId"].stringValue(),
                                    "ledgerKey" to targets[index]["ledgerKey"].stringValue(),
                                    "applicationId" to null,
                                    "participantId" to targets[index]["participantId"]?.longValue(),
                                    "recipientMasked" to "010-****-5678",
                                    "status" to "FAILED",
                                    "providerCode" to "TEST",
                                    "failureReason" to "fake",
                                )
                            },
                    ),
                )
            }
            return """
                {
                    "jobId":"$JOB_ID","previewId":"$previewId","status":"COMPLETED_WITH_FAILURES","createdAt":"2026-10-12T00:00:00Z",
                    "counts":{"total":1,"pending":0,"sending":0,"accepted":0,"delivered":0,"failed":1,"unknown":0,"missing":0},
                    "items":[{"targetId":"target","applicationId":"0190abcd-0000-7000-8000-000000000011","participantId":1,
                    "recipientMasked":"010-****-5678","status":"FAILED","providerCode":"TEST","failureReason":"fake"}]
                }
                """.trimIndent()
        }

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
            registry.add("application.registration.expo-url") { url }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
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
