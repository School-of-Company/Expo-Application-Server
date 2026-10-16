package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationSessionSummary
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.service.impl.AdminAttendanceGateway
import team.startup.application.domain.application.service.impl.AdminParticipantGateway
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class AdminApplicationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var repository: AdminApplicationQueryRepository

    @Autowired private lateinit var participants: AdminParticipantGateway

    private val expoId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val base get() = "/internal/expos/$expoId/program-session-applications"

    @BeforeEach
    fun seed() {
        attendanceStatus = 200
        attendanceBody = null
        attendanceRequests.clear()
        userStatus = 200
        userBody = null
        batchBody = null
        userRequests.clear()
        expoStatus = 200
        expoBody = null
        expoRequests.clear()
        jdbc.execute("TRUNCATE tb_program_session_application, tb_program_session_state CASCADE")
        insert(first, expoId, "CONFIRMED")
        insert(second, expoId, "WAITING")
        insert(UUID.randomUUID(), expoId, "CANCELLED", "TRAINING", 8)
        insert(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED")
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status) VALUES (?, 'CONFIRMED')", first)
        participantIds =
            jdbc.query(
                "SELECT DISTINCT participant_id FROM tb_program_session_application ORDER BY participant_id",
            ) { rs, _ -> rs.getLong(1) }
    }

    private fun insert(
        id: UUID,
        expo: UUID,
        status: String,
        type: String = "STANDARD",
        program: Long = 7,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, status, definition_snapshot, " +
                "form_id, form_version, questions_snapshot, answers_snapshot, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, '{\"title\":\"100% Kotlin\"}', ?, 1, '[{\"id\":1}]', '{\"1\":\"saved\"}', '2026-10-11T00:00:00Z')",
            id,
            expo,
            type,
            program,
            groupId,
            Math.floorMod(id.hashCode(), 100000) + 1,
            status,
            UUID.randomUUID(),
        )
        participantIds =
            jdbc.query(
                "SELECT DISTINCT participant_id FROM tb_program_session_application ORDER BY participant_id",
            ) { rs, _ -> rs.getLong(1) }
    }

    private fun read(query: String = "") = mvc.perform(get("$base$query").header("X-Internal-Token", "test-token")).andReturn().response

    @Test
    fun `사전등록 원장은 참가자 행과 예약 일행을 구분하고 검색 선택 전체 대상이 일치한다`() {
        jdbc.update("INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}')", expoId)
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 1, 1, 'CONFIRMED'), (?, 1, 1, 2, 'CONFIRMED'), (?, 1, 1, 3, 'CANCELLED')",
            expoId,
            expoId,
            expoId,
        )
        val snapshot =
            """{"name":"Waiting","phoneNumber":"010-3333-4444",""" +
                """"informationJson":"{\"companion\":[{\"name\":\"Child\"}]}","questions":[]}"""
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq, withdrawn_at) " +
                "VALUES (?, 'wait', 1, '{}', ?, 2, 'WAITING', 1, NULL), " +
                "(?, 'withdrawn', 1, '{}', ?, 2, 'REJECTED', 2, CURRENT_TIMESTAMP), " +
                "(?, 'pending', 1, '{}', ?, 2, 'RESERVED', NULL, NULL), " +
                "(?, 'completed', 1, '{}', ?, 2, 'COMPLETED', NULL, NULL)",
            expoId,
            snapshot,
            expoId,
            snapshot,
            expoId,
            snapshot,
            expoId,
            snapshot,
        )
        participantIds = listOf(1L, 2L, 3L)
        val path = "/application/admin/expos/$expoId/preregistrations"

        fun readLedger(query: String = "") =
            mvc.perform(get(path + query).header("Authorization", "Bearer original-admin-jwt")).andReturn().response

        fun targets(body: String) =
            mvc
                .perform(
                    post(
                        "$path/targets",
                    ).header("Authorization", "Bearer original-admin-jwt").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andReturn()
                .response
        val response = readLedger()
        assertEquals(200, response.status, response.contentAsString)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val page = mapper.readTree(response.contentAsString)
        assertEquals(6, page["totalElements"].asInt())
        val rows = page["items"].toList()
        assertEquals(1, rows.first { it["participantId"].asLong() == 2L }["representativeId"].asInt())
        val waiting = rows.single { it["status"].asString() == "WAITING" }
        assertTrue(waiting["participantId"].isNull)
        assertEquals(2, waiting["applicants"].asInt())
        assertEquals("Waiting", waiting["reservation"]["name"].asString())
        val detail = readLedger("/detail?key=RESERVATION:$expoId:wait")
        assertEquals(waiting, mapper.readTree(detail.contentAsString))
        val filtered = readLedger("?q=chosen&status=CONFIRMED,CANCELLED&sessionId=1")
        assertEquals(1, mapper.readTree(filtered.contentAsString)["totalElements"].asInt())
        val selection = targets("""{"kind":"FILTERED","filter":{"q":"chosen","status":["CONFIRMED","CANCELLED"],"sessionId":1}}""")
        assertEquals(200, selection.status, selection.contentAsString)
        val selected = mapper.readTree(selection.contentAsString)
        assertEquals(mapper.readTree(filtered.contentAsString)["items"], selected["items"])
        assertEquals(false, selected["messagingSupported"].asBoolean())
        assertEquals(false, selected["reportSupported"].asBoolean())
        val key = selected["items"][0]["key"].asString()
        assertEquals(selected["items"], mapper.readTree(targets("""{"kind":"SELECTED","keys":["$key"]}""").contentAsString)["items"])
        userStatus = 503
        val waitingSearch = readLedger("?status=WAITING&q=33334444")
        assertEquals(200, waitingSearch.status)
        assertEquals(2, mapper.readTree(waitingSearch.contentAsString)["items"][0]["applicants"].asInt())
        userStatus = 200
        assertEquals(0, mapper.readTree(readLedger("?q=missing").contentAsString)["totalElements"].asInt())
        assertEquals(1, mapper.readTree(readLedger("?size=1&page=1").contentAsString)["items"].size())
        assertEquals(404, readLedger("/detail?key=APPLICATION:$expoId:999999999").status)
        assertEquals(404, targets("""{"kind":"SELECTED","keys":["$first"]}""").status)
        assertEquals(404, targets("""{"kind":"SELECTED","keys":["RESERVATION:${UUID.randomUUID()}:wait"]}""").status)
        assertEquals(400, targets("""{"kind":"FILTERED","filter":{"programType":"STANDARD"}}""").status)
        assertEquals(400, targets("""{"kind":"SELECTED","keys":["$key","$key"]}""").status)
        assertEquals(400, targets("""{"kind":"FILTERED","filter":{"page":1}}""").status)
        assertEquals(400, readLedger("?programType=STANDARD").status)
        assertEquals(400, readLedger("?q=a&q=b").status)
        assertEquals(400, readLedger("?size=101").status)
        val other = "/application/admin/expos/${UUID.randomUUID()}/preregistrations"
        assertEquals(
            404,
            mvc
                .perform(get("$other/detail?key=$key").header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(get(path))
                .andReturn()
                .response.status,
        )
        expoStatus = 403
        assertEquals(403, readLedger().status)
        assertEquals(403, targets("""{"kind":"FILTERED","filter":{}}""").status)
    }

    @Test
    fun `사전등록 검색 전체 상한과 삭제 행사 및 원천 누락을 성공으로 바꾸지 않는다`() {
        jdbc.update("INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}')", expoId)
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "SELECT ?, 'bulk-' || n, 1, '{}', '{\"name\":\"Waiting\",\"phoneNumber\":\"01011112222\"}', 1, 'WAITING', n " +
                "FROM generate_series(1, 5001) n",
            expoId,
        )
        val path = "/application/admin/expos/$expoId/preregistrations"
        val tooMany =
            mvc
                .perform(
                    post("$path/targets")
                        .header("Authorization", "Bearer original-admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"kind":"FILTERED","filter":{"status":"WAITING"}}"""),
                ).andReturn()
                .response
        assertEquals(422, tooMany.status, tooMany.contentAsString)
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 999999, 999999, 'CONFIRMED')",
            expoId,
        )
        val missing = mvc.perform(get("$path?status=CONFIRMED").header("Authorization", "Bearer original-admin-jwt")).andReturn().response
        assertEquals(502, missing.status)
        jdbc.update("INSERT INTO tb_preregister_deleted_expo (expo_id) VALUES (?)", expoId)
        assertEquals(
            409,
            mvc
                .perform(get(path).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
    }

    private fun summary(query: String = "") = read("/summary$query")

    @Test
    fun `V21 업그레이드는 과거 주체와 이전 상태를 추정하지 않는다`() {
        val schema = "activity_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val source = requireNotNull(jdbc.dataSource)
        Flyway
            .configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("20")
            .load()
            .migrate()
        try {
            source.connection.use { connection ->
                connection.schema = schema
                try {
                    val scoped = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    scoped.update(
                        "INSERT INTO tb_program_session_application " +
                            "(id, expo_id, program_type, program_id, group_id, participant_id, status, definition_snapshot, " +
                            "form_id, form_version, questions_snapshot, answers_snapshot) " +
                            "VALUES (?, ?, 'STANDARD', 7, ?, 1, 'CANCELLED', '{}', ?, 1, '[]', '{}')",
                        first,
                        expoId,
                        groupId,
                        UUID.randomUUID(),
                    )
                    scoped.update(
                        "INSERT INTO tb_program_session_history(application_id, status, actor_id) " +
                            "VALUES (?, 'WAITING', NULL), (?, 'CONFIRMED', NULL), (?, 'CANCELLED', 99)",
                        first,
                        first,
                        first,
                    )
                    Flyway
                        .configure()
                        .dataSource(source)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                        .migrate()
                    assertEquals(
                        listOf("UNKNOWN:null:false:null", "UNKNOWN:null:false:null", "UNKNOWN:null:false:99"),
                        scoped.query(
                            "SELECT actor_type, previous_status, previous_status_recorded, actor_id FROM tb_program_session_history ORDER BY id",
                        ) { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}:${rs.getBoolean(3)}:${rs.getString(4)}" },
                    )
                } finally {
                    connection.schema = "public"
                }
            }
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
    }

    private fun admin(
        query: String = "",
        authorization: String? = "Bearer original-admin-jwt",
    ) = mvc
        .perform(
            get("/application/admin/expos/$expoId/program-session-applications$query").apply {
                if (authorization != null) header("Authorization", authorization)
            },
        ).andReturn()
        .response

    @Test
    fun `관리자 행사 처리 이력은 신청 취소 승급을 구분하고 행사 범위와 페이지를 지킨다`() {
        jdbc.update(
            "INSERT INTO tb_program_session_state (expo_id, program_type, program_id, definition, deleted) " +
                "VALUES (?, 'STANDARD', 7, '{\"title\":\"Changed title\"}', TRUE)",
            expoId,
        )
        jdbc.update("UPDATE tb_program_session_history SET created_at = '2026-10-11T00:00:00Z' WHERE application_id = ?", first)
        jdbc.update(
            "INSERT INTO tb_program_session_history (application_id, status, created_at) VALUES (?, 'WAITING', '2026-10-11T00:01:00Z')",
            second,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_history (application_id, status, created_at) VALUES (?, 'CONFIRMED', '2026-10-11T00:02:00Z')",
            second,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_history (application_id, status, created_at) VALUES (?, 'CANCELLED', '2026-10-11T00:03:00Z')",
            second,
        )
        val path = "/application/admin/expos/$expoId/program-session-applications/activities"
        assertEquals(
            401,
            mvc
                .perform(get(path))
                .andReturn()
                .response.status,
        )
        expoStatus = 403
        assertEquals(
            403,
            mvc
                .perform(get(path).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        expoStatus = 200
        val response = mvc.perform(get("$path?size=2").header("Authorization", "Bearer original-admin-jwt")).andReturn().response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(4, body["totalElements"].asInt())
        assertEquals("CANCELLED", body["items"][0]["action"].asString())
        assertEquals("PROMOTED", body["items"][1]["action"].asString())
        assertTrue(body["items"][0]["actorId"].isNull)
        assertEquals("UNKNOWN", body["items"][0]["actorType"].asString())
        assertEquals(false, body["items"][0]["previousStatusRecorded"].asBoolean())
        assertTrue(body["items"][0]["previousStatus"].isNull)
        assertEquals("100% Kotlin", body["items"][0]["definition"]["title"].asString())
        assertTrue(body["items"][0]["definition"].get("startedAt") == null)
        assertEquals("Person 3", body["items"][0]["participant"]["name"].asString())
        val applied =
            mapper.readTree(
                mvc
                    .perform(
                        get("$path?action=APPLIED&page=0&size=10").header("Authorization", "Bearer original-admin-jwt"),
                    ).andReturn()
                    .response.contentAsString,
            )
        assertEquals(2, applied["totalElements"].asInt())
        assertEquals(
            0,
            mapper
                .readTree(
                    mvc
                        .perform(
                            get("$path?applicationId=${UUID.randomUUID()}").header("Authorization", "Bearer original-admin-jwt"),
                        ).andReturn()
                        .response.contentAsString,
                )["totalElements"]
                .asInt(),
        )
        assertEquals(
            400,
            mvc
                .perform(get("$path?size=101").header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `이력 복합 검색과 페이지 건수는 현재 참가자와 이력 결과 상태로 동일하게 제한된다`() {
        jdbc.update(
            "INSERT INTO tb_program_session_history (application_id, status, actor_type, previous_status_recorded, created_at) " +
                "VALUES (?, 'WAITING', 'USER', TRUE, '2026-10-12T00:00:00Z')",
            first,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_history " +
                "(application_id, status, actor_type, previous_status, previous_status_recorded, created_at) " +
                "VALUES (?, 'CONFIRMED', 'SYSTEM', 'WAITING', TRUE, '2026-10-12T00:01:00Z')",
            first,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_history " +
                "(application_id, status, actor_type, actor_id, previous_status, previous_status_recorded, created_at) " +
                "VALUES (?, 'CANCELLED', 'ADMIN', 99, 'CONFIRMED', TRUE, '2026-10-12T00:02:00Z')",
            first,
        )
        jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", first)
        val path = "/application/admin/expos/$expoId/program-session-applications/activities"
        val filter =
            "q=chosen&programType=STANDARD&programId=7&applicationId=$first" +
                "&from=2026-10-12T00:00:00Z&until=2026-10-13T00:00:00Z"

        fun query(parameters: String) =
            mvc.perform(get("$path?$parameters").header("Authorization", "Bearer original-admin-jwt")).andReturn().response

        val page = mapper.readTree(query("$filter&size=1").contentAsString)
        assertEquals(3, page["totalElements"].asInt())
        assertEquals(1, page["items"].size())
        val cancelled = page["items"][0]
        assertEquals("ADMIN", cancelled["actorType"].asString())
        assertEquals(99, cancelled["actorId"].asInt())
        assertEquals("CONFIRMED", cancelled["previousStatus"].asString())
        assertTrue(cancelled["previousStatusRecorded"].asBoolean())
        assertTrue(cancelled["receiptNumber"].asLong() > 0)
        val promoted = mapper.readTree(query("$filter&action=PROMOTED&status=CONFIRMED").contentAsString)
        assertEquals(1, promoted["totalElements"].asInt())
        assertEquals("SYSTEM", promoted["items"][0]["actorType"].asString())
        assertEquals("WAITING", promoted["items"][0]["previousStatus"].asString())
        val applied = mapper.readTree(query("$filter&status=WAITING").contentAsString)
        assertEquals(1, applied["totalElements"].asInt())
        assertEquals("USER", applied["items"][0]["actorType"].asString())
        assertTrue(applied["items"][0]["previousStatus"].isNull)
        assertTrue(applied["items"][0]["previousStatusRecorded"].asBoolean())
        val next = mapper.readTree(query("$filter&size=1&page=1").contentAsString)
        assertEquals(3, next["totalElements"].asInt())
        assertEquals(promoted["items"][0]["id"], next["items"][0]["id"])
        for (parameters in listOf(
            "$filter&action=APPLIED&status=CANCELLED",
            filter.replace("chosen", "missing"),
            filter.replace("STANDARD", "TRAINING"),
            filter.replace("programId=7", "programId=8"),
            "$filter&page=10",
        )) {
            val empty = mapper.readTree(query(parameters).contentAsString)
            assertEquals(0, empty["items"].size(), parameters)
            assertEquals(if (parameters.endsWith("page=10")) 3 else 0, empty["totalElements"].asInt(), parameters)
        }
        val other = UUID.randomUUID()
        insert(other, UUID.randomUUID(), "CONFIRMED")
        jdbc.update("INSERT INTO tb_program_session_history(application_id, status) VALUES (?, 'CONFIRMED')", other)
        assertEquals(0, mapper.readTree(query("applicationId=$other").contentAsString)["totalElements"].asInt())
        assertEquals(
            400,
            mvc
                .perform(get(path).param("q", " ").header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        for (invalid in listOf("q=${"a".repeat(51)}", "programId=7", "programType=STANDARD&programId=0", "status=OTHER", "q=a&q=b")) {
            assertEquals(400, query(invalid).status, invalid)
        }
        userStatus = 503
        assertEquals(503, query("q=chosen").status)
        userStatus = 200
        userBody = """{"items":[],"nextCursor":null}"""
        assertEquals(502, query("applicationId=$first").status)
    }

    @Test
    fun `관리자 사전등록 집계는 고유 참가자와 예약 인원을 분리한다`() {
        jdbc.execute(
            "TRUNCATE tb_preregister_transition, tb_preregister_application, tb_preregister_reservation, tb_preregister_session_state CASCADE",
        )
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}'), (?, 2, '{}')",
            expoId,
            expoId,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 10, 10, 'CONFIRMED'), (?, 1, 10, 11, 'CONFIRMED'), (?, 2, 10, 10, 'CANCELLED')",
            expoId,
            expoId,
            expoId,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants, state) " +
                "VALUES (?, 'pending', 2, '{}', '{}', 3, 'RESERVED'), (?, 'done', 1, '{}', '{}', 2, 'COMPLETED')",
            expoId,
            expoId,
        )
        val questions =
            """[{"id":1,"title":"동행","order":0,"formType":"COMPANION","jsonData":{},""" +
                """"otherJson":null,"dynamicFormType":"COMPANION"}]"""
        val companions =
            """[{"name":"A","occupation":"GENERAL","region":"OTHER"},{"name":" A ","occupation":"GENERAL","region":"OTHER"},""" +
                """{"name":"B","occupation":"TEACHER","school":"S","region":"OTHER"}]"""

        fun waiting(occupation: String?) =
            mapper.writeValueAsString(
                mapOf(
                    "expoId" to expoId.toString(),
                    "name" to "Waiting",
                    "phoneNumber" to "01000000000",
                    "informationJson" to if (occupation == null) "{}" else """{"동행":$companions}""",
                    "personalInformationStatus" to true,
                    "applicationType" to "PRE",
                    "occupation" to occupation,
                    "requestId" to UUID.randomUUID().toString(),
                    "questions" to mapper.readTree(questions),
                ),
            )
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "VALUES (?, 'wait-1', 1, '{}', ?, 3, 'WAITING', 1), (?, 'wait-2', 1, '{}', ?, 1, 'WAITING', 2)",
            expoId,
            waiting("PRE_SERVICE_TEACHER"),
            expoId,
            waiting(null),
        )
        participantIds = listOf(10L, 11L)
        userBody =
            """{"items":[""" +
            listOf(10 to "\"TEACHER\"", 11 to "null").joinToString(",") { (id, occupation) ->
                """{"participantId":$id,"name":"P","phoneNumber":null,"notificationPhoneNumber":null,"occupation":$occupation,""" +
                    """"representativeId":10,"linkedTraineeId":null,"applicationType":"PRE","information":{}}"""
            } + """],"nextCursor":null}"""
        val path = "/application/admin/expos/$expoId/preregistrations/summary"
        assertEquals(
            401,
            mvc
                .perform(get(path))
                .andReturn()
                .response.status,
        )
        expoStatus = 403
        assertEquals(
            403,
            mvc
                .perform(get(path).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        expoStatus = 200
        val response = mvc.perform(get(path).header("Authorization", "Bearer original-admin-jwt")).andReturn().response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(2, body["confirmedUniqueParticipants"].asInt())
        assertEquals(1, body["cancelledUniqueParticipants"].asInt())
        assertEquals(4, body["waitingParticipants"].asInt())
        assertEquals(1, body["pendingReservations"].asInt())
        val occupations =
            body["occupations"].associate { row ->
                row["occupation"].takeUnless { it.isNull }?.asString() to
                    (row["confirmedParticipants"].asInt() to row["waitingParticipants"].asInt())
            }
        assertEquals(10, occupations.size)
        assertEquals(1 to 1, occupations["TEACHER"])
        assertEquals(0 to 1, occupations["PRE_SERVICE_TEACHER"])
        assertEquals(0 to 1, occupations["GENERAL"])
        assertEquals(1 to 1, occupations[null])
        assertEquals(body["confirmedUniqueParticipants"].asInt(), occupations.values.sumOf { it.first })
        assertEquals(body["waitingParticipants"].asInt(), occupations.values.sumOf { it.second })
        assertEquals(3, body["pendingApplicants"].asInt())
        assertEquals(2, body["sessions"].size())
        assertEquals(2, body["sessions"][0]["confirmedParticipants"].asInt())
        assertEquals(0, body["sessions"][1]["confirmedParticipants"].asInt())
        assertEquals(1, body["sessions"][1]["cancelledParticipants"].asInt())
        assertEquals(3, body["sessions"][1]["pendingApplicants"].asInt())
    }

    @Test
    fun `관리자 참가자 검색은 권한 검증 뒤 User 원천 커서 페이지를 그대로 전달한다`() {
        val path = "/application/admin/expos/$expoId/participants"
        assertEquals(
            401,
            mvc
                .perform(get("$path?q=chosen"))
                .andReturn()
                .response.status,
        )
        expoStatus = 403
        assertEquals(
            403,
            mvc
                .perform(get("$path?q=chosen").header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        assertTrue(userRequests.isEmpty())
        expoStatus = 200
        val response = mvc.perform(get("$path?q=chosen&size=5").header("Authorization", "Bearer original-admin-jwt")).andReturn().response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(1, body["items"].size())
        assertEquals(2L, body["items"][0]["participantId"].asLong())
        assertEquals("010-1234-5678", body["items"][0]["notificationPhoneNumber"].asString())
        assertTrue(body["nextCursor"].isNull)
        assertTrue(userRequests.single().endsWith("cursor=0&size=5&q=chosen"))
        for (query in listOf(
            "",
            "?q=",
            "?q=chosen&size=101",
            "?q=chosen&cursor=-1",
            "?q=chosen&occupation=TEACHER",
            "?q=a&q=b",
        )) {
            assertEquals(
                400,
                mvc
                    .perform(get("$path$query").header("Authorization", "Bearer original-admin-jwt"))
                    .andReturn()
                    .response.status,
                query,
            )
        }
        assertEquals(
            400,
            mvc
                .perform(get(path).param("q", " ").header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        userBody = """{"items":[{"participantId":0}],"nextCursor":null}"""
        assertEquals(
            502,
            mvc
                .perform(get("$path?q=chosen").header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `관리자 원본 JWT와 현재 권한은 모든 개인정보 원천 조회 전에 검증한다`() {
        for (suffix in listOf("", "/summary", "/current-sessions")) {
            assertEquals(401, admin(suffix, null).status)
            assertEquals(401, admin(suffix, "Basic ignored").status)
            for (status in listOf(401, 403, 404, 503)) {
                expoStatus = status
                assertEquals(status, admin(suffix).status)
            }
        }
        assertTrue(userRequests.isEmpty())
        assertTrue(attendanceRequests.isEmpty())
        expoStatus = 200
        val response = admin("?applicationId=$first")
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val item = mapper.readTree(response.contentAsString)["items"][0]
        assertEquals("saved", item["answers"]["1"].asString())
        assertEquals("current", item["participant"]["information"]["answer"].asString())
        assertEquals("CONFIRMED", item["history"][0]["status"].asString())
        assertTrue(item["participant"]["phoneNumber"].isNull)
        assertEquals("010-1234-5678", item["participant"]["notificationPhoneNumber"].asString())
        assertTrue(expoRequests.all { it == "Bearer original-admin-jwt" })
        expoStatus = 403
        userRequests.clear()
        assertEquals(403, admin().status)
        assertTrue(userRequests.isEmpty())
    }

    @Test
    fun `잘못된 권한 성공 응답과 공급자 오류는 허용이나 빈 결과로 바뀌지 않는다`() {
        for (body in listOf(
            "{}",
            "not-json",
            """{"expoId":"$expoId","adminId":1,"permission":"WRITE","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}""",
            """{"expoId":"${UUID.randomUUID()}","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}""",
        )) {
            expoBody = body
            assertEquals(502, admin().status)
            assertTrue(userRequests.isEmpty())
        }
        expoBody = null
        for (status in listOf(400, 429, 302, 500)) {
            expoStatus = status
            assertEquals(if (status == 500) 503 else 502, admin().status)
        }
    }

    @Test
    fun `User 사람 후보는 목록 건수 요약 Report 대상 모두 페이지 이전에 교차한다`() {
        val candidate = Math.floorMod(first.hashCode(), 100000).toLong() + 1
        val otherApplication = UUID.randomUUID()
        insert(otherApplication, expoId, "CANCELLED", "STANDARD", 10)
        jdbc.update("UPDATE tb_program_session_application SET participant_id = ? WHERE id = ?", candidate, otherApplication)
        val expected =
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application WHERE expo_id = ? AND participant_id = ?",
                Long::class.java,
                expoId,
                candidate,
            )
        val filter = "?q=chosen&occupation=TEACHER&size=1"
        val list = mapper.readTree(admin(filter).contentAsString)
        val summary = mapper.readTree(admin("/summary$filter").contentAsString)
        assertEquals(expected, list["totalElements"].asLong())
        assertEquals(list["statusCounts"], summary["statusCounts"])
        assertEquals(list["totalElements"], summary["totalElements"])
        assertEquals(1, summary["uniqueParticipants"].asInt())
        val candidates = participants.resolve(expoId, "chosen", "TEACHER")
        assertEquals(listOf(candidate), candidates)
        assertEquals(expected?.toInt(), repository.targets(expoId, AdminApplicationQuery(), 100, participantIds = candidates).size)
        val empty = mapper.readTree(admin("?q=missing").contentAsString)
        assertEquals(0, empty["totalElements"].asInt())
        assertEquals(0, repository.targets(expoId, AdminApplicationQuery(), 100, participantIds = emptyList()).size)
        assertEquals(0, mapper.readTree(admin("/summary?q=missing").contentAsString)["totalElements"].asInt())
        assertTrue(userRequests.any { it.contains("q=chosen") && it.contains("occupation=TEACHER") })
    }

    @Test
    fun `User 실패와 누락 순서불일치 비정상커서는 조회 전체를 실패시킨다`() {
        for (status in listOf(400, 401, 403, 404, 429, 500, 503)) {
            userStatus = status
            assertEquals(if (status >= 500) 503 else 502, admin("?q=chosen").status)
            assertEquals(if (status >= 500) 503 else 502, admin("/summary?q=chosen").status)
        }
        userStatus = 200
        for (body in listOf("{}", "not-json", """{"items":[],"nextCursor":1}""", """{"items":[],"nextCursor":"bad"}""")) {
            userBody = body
            assertEquals(502, admin("?q=chosen").status)
        }
        userBody = null
        batchBody = "[]"
        assertEquals(502, admin("?q=chosen").status)
        assertEquals(502, admin("/summary?q=chosen").status)
        batchBody = """[{"participantId":999999}]"""
        assertEquals(502, admin("?q=chosen").status)
    }

    @Test
    fun `삭제된 User 참가자 한 명도 관리자 전체 목록을 실패시키며 원장 답변과 이력은 보존한다`() {
        val before = read("?sort=CREATED_ASC")
        assertEquals(200, before.status)
        participantIds = participantIds.filterNot { it == 2L }
        val missing = admin("?sort=CREATED_ASC")
        assertEquals(502, missing.status)
        assertTrue(userRequests.none { it.startsWith("batch:") })
        val after = read("?sort=CREATED_ASC")
        assertEquals(200, after.status)
        assertEquals(mapper.readTree(before.contentAsString), mapper.readTree(after.contentAsString))
        val saved = mapper.readTree(after.contentAsString)["items"][0]
        assertEquals("saved", saved["answers"]["1"].asString())
        assertEquals("CONFIRMED", saved["history"][0]["status"].asString())
        assertTrue(saved["participant"].isNull)
    }

    @Test
    fun `current sessions accept all Expo session time formats`() {
        val page = mapper.readTree(read("/current-sessions").contentAsString).asObject()
        val session = page["items"][0].asObject()
        for ((start, end) in listOf(
            "2026-10-12 09:00" to "2026-10-12 10:00",
            "2026-10-12T09:00" to "2026-10-12T10:00",
            "2026-10-12T09:00:00+09:00" to "2026-10-12T10:00:00+09:00",
        )) {
            session.put("startedAt", start)
            session.put("endedAt", end)
            expoBody = mapper.writeValueAsString(page)
            assertEquals(200, read("/current-sessions").status)
        }
    }

    @Test
    fun `admin snapshot venue stays historical while current session venue changes independently`() {
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = " +
                "jsonb_set(definition_snapshot, '{place}', to_jsonb(CAST(? AS text))) WHERE id = ?",
            "Original room",
            first,
        )

        fun snapshot() = mapper.readTree(read("?applicationId=$first").contentAsString)["items"][0]["definition"]
        assertEquals("Original room", snapshot()["place"].asString())
        val current = mapper.readTree(admin("/current-sessions").contentAsString).asObject()
        current["items"][0].asObject().put("place", "Current room")
        expoBody = mapper.writeValueAsString(current)
        assertEquals("Current room", mapper.readTree(read("/current-sessions").contentAsString)["items"][0]["place"].asString())
        assertEquals("Original room", snapshot()["place"].asString())
        jdbc.update("UPDATE tb_program_session_application SET definition_snapshot = definition_snapshot - 'place' WHERE id = ?", first)
        assertFalse(snapshot().has("place"))
    }

    @Test
    fun `current session seats expose pre onsite and unknown usage without counting the ledger twice`() {
        val page = mapper.readTree(read("/current-sessions").contentAsString).asObject()
        val session = page["items"][0].asObject()
        session.set(
            "policy",
            mapper.readTree(
                """{"formId":"${UUID.randomUUID()}","allowedOccupations":["TEACHER"],"capacity":5,"preRegistrationCapacity":2,"onsiteCapacity":3,"waitingCapacity":0,"applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"2100-01-01T00:00:00Z","cancelEndedAt":"2100-01-01T00:00:00Z","promotionEndedAt":"2100-01-01T00:00:00Z","closed":false}""",
            ),
        )
        expoBody = page.toString()
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (1001, 99)")
        try {
            val response = read("/current-sessions")
            assertEquals(200, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            val seats = mapper.readTree(response.contentAsString)["items"][0]["admissionSeats"]
            assertEquals(1, seats["unknownConfirmedCount"].asInt())
            assertEquals(0, seats["preRegistration"]["confirmedCount"].asInt())
            assertEquals(1, seats["preRegistration"]["remaining"].asInt())
            assertEquals(2, seats["onsite"]["remaining"].asInt())
            assertFalse(seats["onsiteAdmissionEnabled"].asBoolean())
            val row = mapper.readTree(read("?applicationId=$first").contentAsString)["items"][0]
            assertTrue(row["admissionRoute"].isNull)
        } finally {
            jdbc.update("DELETE FROM tb_standard_program_application WHERE participant_id = 1001 AND standard_program_id = 99")
        }
    }

    @Test
    fun `current session malformed admission capacities fail closed`() {
        val page = mapper.readTree(read("/current-sessions").contentAsString).asObject()
        page["items"][0].asObject().set(
            "policy",
            mapper.readTree(
                """{"formId":"${UUID.randomUUID()}","allowedOccupations":["TEACHER"],"capacity":5,"preRegistrationCapacity":2,"onsiteCapacity":2,"waitingCapacity":0,"applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"2100-01-01T00:00:00Z","cancelEndedAt":"2100-01-01T00:00:00Z","promotionEndedAt":"2100-01-01T00:00:00Z","closed":false}""",
            ),
        )
        expoBody = page.toString()
        assertEquals(502, read("/current-sessions").status)
    }

    @Test
    fun `현재 회차는 빈 접수에도 조회하며 버전 변경과 타 행사 선택을 구분한다`() {
        val response = admin("/current-sessions")
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(99, mapper.readTree(response.contentAsString)["items"][0]["id"].asInt())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE program_id = 99", Int::class.java))
        assertEquals(400, admin("/current-sessions?page=1").status)
        assertEquals(400, admin("/current-sessions?programId=99").status)
        for (status in listOf(404, 409, 503)) {
            expoStatus = status
            assertEquals(status, read("/current-sessions?programType=STANDARD&programId=99").status)
        }
        expoStatus = 200
        expoBody = "{}"
        assertEquals(502, admin("/current-sessions").status)
    }

    @Test
    fun `사람 필터 검증과 제목 필터는 모든 관리자 조회에 동일하게 적용한다`() {
        for (filter in listOf("q= ", "q=${"a".repeat(51)}", "q=one&q=two", "occupation=ADMIN", "occupation=", "unknown=1")) {
            assertEquals(400, admin("?$filter").status)
            assertEquals(400, admin("/summary?$filter").status)
        }
        assertEquals(0, mapper.readTree(admin("?search=missing&q=chosen").contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `후보 커서와 확정 배치는 500명 경계를 유지하며 무필터 요약은 User 전체를 훑지 않는다`() {
        participantIds = (1L..501L).toList()
        assertEquals(participantIds, participants.resolve(expoId, null, "TEACHER"))
        assertEquals(2, userRequests.count { it.startsWith("/internal/expos/") })
        assertEquals(2, userRequests.count { it.startsWith("batch:") })
        assertTrue(userRequests.any { it.contains("cursor=500") })
        userRequests.clear()
        userStatus = 503
        assertEquals(200, admin("/summary").status)
        assertTrue(userRequests.isEmpty())
        val internalList = read()
        assertEquals(200, internalList.status)
        assertTrue(mapper.readTree(internalList.contentAsString)["items"][0]["participant"].isNull)
        assertTrue(userRequests.isEmpty())
    }

    @Test
    fun `사람 원천 정수 오버플로와 중복 ID는 잘못된 성공이다`() {
        val id = "9223372036854775808"
        userBody =
            """{"items":[{"participantId":$id,"name":"Person","phoneNumber":null,"notificationPhoneNumber":null,"occupation":"TEACHER","representativeId":null,"linkedTraineeId":null,"applicationType":"PRE","information":{}}],"nextCursor":null}"""
        assertEquals(502, admin("?q=chosen").status)
        userBody = null
        batchBody = """[{"participantId":$id}]"""
        assertEquals(502, admin("?q=chosen").status)
        participantIds = listOf(2L, 2L)
        batchBody = null
        assertEquals(502, admin("?q=chosen").status)
    }

    @Test
    fun `넓은 직업 후보 7만 명도 바인드 상한 없이 동일한 원장 집합을 조회한다`() {
        val candidates = ((1L..70_000L).toList() + participantIds).distinct()
        val query = AdminApplicationQuery(size = 1)
        val (rows, counts) = repository.query(expoId, query, candidates)
        val summary = repository.summary(expoId, query, candidates)
        assertEquals(1, rows.size)
        assertEquals(3L, counts.values.sum())
        assertEquals(counts, summary.statusCounts)
        assertEquals(3L, summary.totalElements)
        assertEquals(3, repository.targets(expoId, query, 100, participantIds = candidates).size)
    }

    @Test
    fun `출석 조회 미설정과 연결 실패는 서비스 불가이며 중복 회차는 한 번만 요청한다`() {
        val session = AdminApplicationSessionSummary(ProgramSessionType.STANDARD, 7, groupId, 1, 1, emptyMap())
        val missing =
            assertThrows(ResponseStatusException::class.java) { AdminAttendanceGateway(mapper, "", "").summarize(listOf(session)) }
        assertEquals(503, missing.statusCode.value())
        val stopped = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${stopped.address.port}"
        stopped.start()
        stopped.stop(0)
        val unavailable =
            assertThrows(ResponseStatusException::class.java) {
                AdminAttendanceGateway(mapper, url, "attendance-token").summarize(listOf(session))
            }
        assertEquals(503, unavailable.statusCode.value())
        val result =
            AdminAttendanceGateway(mapper, "http://127.0.0.1:${attendance.address.port}", "attendance-token")
                .summarize(listOf(session, session.copy(groupId = UUID.randomUUID())))
        assertEquals(1, result.size)
        assertEquals(1, mapper.readTree(attendanceRequests.single())["programs"].size())
    }

    @Test
    fun `실제 출석은 접수 필터와 구분하고 유형과 회차 ID로 공급자를 조회한다`() {
        insert(UUID.randomUUID(), expoId, "CANCELLED", "TRAINING", 7)
        val body = mapper.readTree(summary("?sort=CREATED_ASC").contentAsString)
        val standard = body["sessions"].first { it["programType"].asString() == "STANDARD" }
        val training = body["sessions"].first { it["programType"].asString() == "TRAINING" && it["programId"].asInt() == 7 }
        assertEquals(2, standard["attendance"]["attendeeCount"].asInt())
        assertEquals("09:05", standard["attendance"]["firstEntryTime"].asString())
        assertEquals("10:30", standard["attendance"]["lastEntryTime"].asString())
        assertEquals(0, training["attendance"]["attendeeCount"].asInt())
        assertTrue(training["attendance"]["firstEntryTime"].isNull)
        assertTrue(attendanceRequests.single().contains("\"programType\":\"TRAINING\",\"programId\":7"))
        val filtered = mapper.readTree(summary("?status=WAITING&programType=STANDARD&programId=7").contentAsString)
        assertEquals(1, filtered["totalElements"].asInt())
        assertEquals(2, filtered["sessions"][0]["attendance"]["attendeeCount"].asInt())
    }

    @Test
    fun `출석 공급자 오류와 잘못된 성공을 0으로 위장하지 않는다`() {
        for (status in listOf(400, 401, 403, 404, 409, 429, 302, 204, 500, 503)) {
            attendanceStatus = status
            assertEquals(if (status >= 500) 503 else 502, summary("?programType=STANDARD&programId=7").status, "HTTP $status")
        }
        attendanceStatus = 200
        val valid = """{"programType":"STANDARD","programId":7,"attendeeCount":2,"firstEntryTime":"09:05","lastEntryTime":"10:30"}"""
        for (body in listOf(
            "not-json",
            "[]",
            "[$valid,$valid]",
            "[${valid.replace("STANDARD", "TRAINING")}]",
            "[${valid.replace("\"programId\":7", "\"programId\":8")}]",
            "[${valid.replace("\"attendeeCount\":2", "\"attendeeCount\":-1")}]",
            "[${valid.replace("09:05", "25:05")}]",
            "[${valid.replace("09:05", "11:00")}]",
            "[${valid.replace("\"attendeeCount\":2", "\"attendeeCount\":0")}]",
            """[{"programType":"STANDARD","programId":7}]""",
        )) {
            attendanceBody = body
            assertEquals(502, summary("?programType=STANDARD&programId=7").status, body)
        }
        attendanceStatus = 503
        attendanceRequests.clear()
        assertEquals(200, summary("?applicationId=${UUID.randomUUID()}").status)
        assertTrue(attendanceRequests.isEmpty())
    }

    @Test
    fun `회차 집계는 목록과 Report 전체 대상과 같은 필터를 적용한다`() {
        val filter = "?programType=STANDARD&programId=7&groupId=$groupId&search=KOTLIN&sort=CREATED_ASC"
        val list = mapper.readTree(read(filter).contentAsString)
        val response = summary(filter)
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(list["totalElements"], body["totalElements"])
        assertEquals(list["statusCounts"], body["statusCounts"])
        assertEquals(1, body["totalSessions"].asInt())
        assertEquals(2, body["uniqueParticipants"].asInt())
        assertEquals(7, body["sessions"][0]["programId"].asInt())
        assertEquals(body["statusCounts"], body["sessions"][0]["statusCounts"])
        val report =
            mvc
                .perform(
                    post("$base/report-targets")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"filter":{"programType":"STANDARD","programId":7,"groupId":"$groupId","search":"KOTLIN","sort":"CREATED_ASC"}}""",
                        ),
                ).andReturn()
                .response
        assertEquals(200, report.status)
        assertEquals(body["totalElements"].asLong(), mapper.readTree(report.contentAsString)["totalElements"].asLong())
    }

    @Test
    fun `고유 참가자는 회차 신청 건수와 다르며 집계 페이지는 전체 건수를 바꾸지 않는다`() {
        val extra = UUID.randomUUID()
        insert(extra, expoId, "CANCELLED", "STANDARD", 9)
        jdbc.update(
            "UPDATE tb_program_session_application SET participant_id = (SELECT participant_id FROM tb_program_session_application WHERE id = ?) WHERE id = ?",
            first,
            extra,
        )
        val body = mapper.readTree(summary("?size=1&sort=CREATED_ASC").contentAsString)
        assertEquals(4, body["totalElements"].asInt())
        assertEquals(3, body["uniqueParticipants"].asInt())
        assertEquals(3, body["totalSessions"].asInt())
        assertEquals(1, body["sessions"].size())
        assertEquals("STANDARD", body["sessions"][0]["programType"].asString())
        assertEquals(7, body["sessions"][0]["programId"].asInt())
        val next = mapper.readTree(summary("?size=1&page=1&sort=CREATED_ASC").contentAsString)
        assertEquals(9, next["sessions"][0]["programId"].asInt())
        assertEquals(body["totalElements"], next["totalElements"])
        val empty = mapper.readTree(summary("?page=99").contentAsString)
        assertEquals(0, empty["sessions"].size())
        assertEquals(body["totalElements"], empty["totalElements"])
        val cancelled = mapper.readTree(summary("?status=CANCELLED").contentAsString)
        assertEquals(2, cancelled["totalElements"].asInt())
        assertEquals(0, cancelled["statusCounts"]["CONFIRMED"].asInt())
    }

    @Test
    fun `회차 집계는 행사 격리와 필터 검증 및 내부 인증을 유지한다`() {
        assertEquals(
            401,
            mvc
                .perform(get("$base/summary"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(get("$base/summary").header("X-User-Role", "ROLE_ADMIN"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(get("$base/summary").header("X-Internal-Token", "wrong"))
                .andReturn()
                .response.status,
        )
        listOf(
            "role=ADMIN",
            "search=",
            "status=WAITING&status=CANCELLED",
            "size=101",
            "programId=7",
            "from=2026-10-12T00:00:00Z&until=2026-10-11T00:00:00Z",
        ).forEach { assertEquals(400, summary("?$it").status, it) }
        val empty = mapper.readTree(summary("?applicationId=${UUID.randomUUID()}").contentAsString)
        assertEquals(0, empty["totalElements"].asInt())
        assertEquals(0, empty["uniqueParticipants"].asInt())
        assertEquals(0, empty["totalSessions"].asInt())
        assertEquals(0, empty["sessions"].size())
        val response =
            mvc
                .perform(
                    get("/internal/expos/${UUID.randomUUID()}/program-session-applications/summary?applicationId=$first")
                        .header("X-Internal-Token", "test-token"),
                ).andReturn()
                .response
        assertEquals(200, response.status)
        assertEquals(0, mapper.readTree(response.contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `집계는 동시 취소 이후에도 같은 DB 스냅샷을 유지한다`() {
        val transaction = TransactionTemplate(transactionManager)
        transaction.isReadOnly = true
        transaction.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        transaction.executeWithoutResult {
            val query =
                AdminApplicationQuery(
                    status = listOf(team.startup.application.domain.application.presentation.dto.AdminApplicationStatus.CONFIRMED),
                )
            val before = repository.summary(expoId, query)
            CompletableFuture
                .runAsync {
                    jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", first)
                }.get(10, TimeUnit.SECONDS)
            assertEquals(before, repository.summary(expoId, query))
        }
        assertEquals(0, mapper.readTree(summary("?status=CONFIRMED").contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `필터 건수 상태 집계와 안정된 페이지는 같은 원장을 조회한다`() {
        val filter =
            "?programType=STANDARD&programId=7&groupId=$groupId&search=KOTLIN" +
                "&from=2026-10-11T00:00:00Z&until=2026-10-12T00:00:00Z&sort=CREATED_ASC&size=1"
        val response = read(filter)
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(2, body["totalElements"].asInt())
        assertEquals(1, body["statusCounts"]["CONFIRMED"].asInt())
        assertEquals(1, body["statusCounts"]["WAITING"].asInt())
        assertEquals(first.toString(), body["items"][0]["applicationId"].asString())
        assertEquals(second.toString(), mapper.readTree(read("$filter&page=1").contentAsString)["items"][0]["applicationId"].asString())
        assertEquals(2, mapper.readTree(read("$filter&page=99").contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `접수번호는 박람회별로 1부터 증가하며 롤백된 접수는 번호를 소비하지 않는다`() {
        val numbers = mapper.readTree(read("?sort=RECEIPT_ASC").contentAsString)["items"].asIterable().map { it["receiptNumber"].asLong() }
        assertEquals(listOf(1L, 2L, 3L), numbers)
        assertThrows(Exception::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                insert(UUID.randomUUID(), expoId, "CONFIRMED", program = 11)
                error("rollback")
            }
        }
        val next = UUID.randomUUID()
        insert(next, expoId, "CONFIRMED", program = 12)
        val body = mapper.readTree(read("?sort=RECEIPT_DESC&size=1").contentAsString)
        assertEquals(next.toString(), body["items"][0]["applicationId"].asString())
        assertEquals(4, body["items"][0]["receiptNumber"].asLong())
        val other = UUID.randomUUID()
        insert(UUID.randomUUID(), other, "CONFIRMED")
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT receipt_number FROM tb_program_session_application WHERE expo_id = ?", Long::class.java, other),
        )
        assertThrows(Exception::class.java) {
            jdbc.update("UPDATE tb_program_session_application SET receipt_number = 1 WHERE id = ?", next)
        }
    }

    @Test
    fun `복수 상태와 행사일 필터 및 프로그램 회차 정렬은 목록과 집계에 같은 원장을 적용한다`() {
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = '{\"title\":\"B\",\"startedAt\":\"2026-10-13 09:00\"}' WHERE id = ?",
            first,
        )
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = '{\"title\":\"A\",\"startedAt\":\"2026-10-13T10:00\"}' WHERE id = ?",
            second,
        )
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = '{\"title\":\"C\",\"startedAt\":\"2026-10-12T08:00\"}' " +
                "WHERE expo_id = ? AND status = 'CANCELLED'",
            expoId,
        )
        val active = mapper.readTree(read("?status=CONFIRMED,WAITING").contentAsString)
        assertEquals(2, active["totalElements"].asInt())
        assertEquals(0, active["statusCounts"]["CANCELLED"].asInt())
        assertEquals(active["statusCounts"], mapper.readTree(summary("?status=CONFIRMED,WAITING").contentAsString)["statusCounts"])
        assertEquals(1, mapper.readTree(read("?status=CANCELLED").contentAsString)["totalElements"].asInt())

        val day = mapper.readTree(read("?eventDate=2026-10-13&sort=SESSION_ASC").contentAsString)
        assertEquals(listOf(first, second).map(UUID::toString), day["items"].asIterable().map { it["applicationId"].asString() })
        assertEquals(2, mapper.readTree(summary("?eventDate=2026-10-13").contentAsString)["totalElements"].asInt())
        assertEquals(0, mapper.readTree(read("?eventDate=2026-10-14").contentAsString)["totalElements"].asInt())

        fun order(sort: String) =
            mapper.readTree(read("?sort=$sort").contentAsString)["items"].asIterable().map {
                it["definition"]["title"].asString()
            }
        assertEquals(listOf("C", "B", "A"), order("SESSION_ASC"))
        assertEquals(listOf("A", "B", "C"), order("SESSION_DESC"))
        assertEquals(listOf("A", "B", "C"), order("PROGRAM_ASC"))
        assertEquals(listOf("C", "B", "A"), order("PROGRAM_DESC"))
        listOf("status=CONFIRMED,OTHER", "eventDate=2026-13-01", "eventDate=20261013", "sort=NAME_ASC").forEach {
            assertEquals(400, read("?$it").status, it)
        }
    }

    @Test
    fun `상세는 당시 답변과 이력을 반환하며 타 행사 ID는 노출하지 않는다`() {
        val body = mapper.readTree(read("?applicationId=$first&status=CONFIRMED").contentAsString)
        assertEquals("saved", body["items"][0]["answers"]["1"].asString())
        assertEquals("CONFIRMED", body["items"][0]["history"][0]["status"].asString())
        assertTrue(body["items"][0]["traineeId"].isNull)
        // #122: a training row shows the ledger's own participant and linked trainee IDs.
        jdbc.update("UPDATE tb_program_session_application SET program_type = 'TRAINING', trainee_id = 509 WHERE id = ?", first)
        val training = mapper.readTree(read("?applicationId=$first").contentAsString)["items"][0]
        assertEquals(509L, training["traineeId"].asLong())
        assertEquals(
            jdbc.queryForObject("SELECT participant_id FROM tb_program_session_application WHERE id = ?", Long::class.java, first),
            training["participantId"].asLong(),
        )
        val other =
            mvc
                .perform(
                    get(
                        "/internal/expos/${UUID.randomUUID()}/program-session-applications?applicationId=$first",
                    ).header("X-Internal-Token", "test-token"),
                ).andReturn()
                .response
        assertEquals(0, mapper.readTree(other.contentAsString)["totalElements"].asInt())
        assertEquals(0, mapper.readTree(read("?search=missing").contentAsString)["totalElements"].asInt())
        assertEquals(0, mapper.readTree(read("?until=2026-10-11T00:00:00Z").contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `검색 특수문자는 패턴이 아니며 취소 이력도 기본 집계에 포함된다`() {
        val response = mvc.perform(get(base).param("search", "%").header("X-Internal-Token", "test-token")).andReturn().response
        val body = mapper.readTree(response.contentAsString)
        assertEquals(3, body["totalElements"].asInt())
        assertEquals(1, body["statusCounts"]["CANCELLED"].asInt())
        assertEquals(0, mapper.readTree(read("?search=_").contentAsString)["totalElements"].asInt())
        assertEquals(
            second.toString(),
            mapper.readTree(read("?programType=STANDARD&size=1").contentAsString)["items"][0]["applicationId"].asString(),
        )
        val cancelled = mapper.readTree(read("?programType=TRAINING&programId=8&status=CANCELLED").contentAsString)
        assertEquals(1, cancelled["totalElements"].asInt())
        assertEquals(0, cancelled["statusCounts"]["CONFIRMED"].asInt())
    }

    @Test
    fun `이력 시각은 DB 시간대와 무관하게 접수 시각과 동일한 UTC 형식이다`() {
        jdbc.update("UPDATE tb_program_session_application SET created_at = '2026-10-11T00:00:00.123456Z' WHERE id = ?", first)
        jdbc.update("UPDATE tb_program_session_history SET created_at = '2026-10-11T00:00:00.123456Z' WHERE application_id = ?", first)
        val transaction = TransactionTemplate(transactionManager)
        transaction.isReadOnly = true
        transaction.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        transaction.executeWithoutResult {
            jdbc.execute("SET LOCAL TIME ZONE 'Asia/Seoul'")
            val rows = repository.query(expoId, AdminApplicationQuery(applicationId = first)).first
            val item: tools.jackson.databind.JsonNode = mapper.valueToTree(rows.single())
            assertEquals("2026-10-11T00:00:00.123456Z", item["createdAt"].asString())
            assertEquals(item["createdAt"].asString(), item["history"][0]["createdAt"].asString())
        }
    }

    @Test
    fun `관리자 전체 상태 조회와 이력 조회용 인덱스가 마이그레이션된다`() {
        val indexes =
            jdbc.query(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN ('ix_program_session_admin_page', 'ix_program_session_history_application')",
            ) { rs, _ -> requireNotNull(rs.getString("indexdef")) }
        assertEquals(2, indexes.size)
        assertTrue(indexes.any { it.contains("(expo_id, created_at, id)") })
        assertTrue(indexes.any { it.contains("(application_id, created_at, id)") })
        assertTrue(indexes.none { it.contains("WHERE") })
    }

    @Test
    fun `토큰과 유효한 지원 필터가 필요하다`() {
        assertEquals(
            401,
            mvc
                .perform(get(base).header("X-User-Role", "ROLE_ADMIN"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(get(base).header("X-Internal-Token", "wrong"))
                .andReturn()
                .response.status,
        )
        listOf(
            "page=-1",
            "size=101",
            "status=OTHER",
            "sort=id",
            "programId=7",
            "programType=STANDARD&programId=0",
            "role=ADMIN",
            "status=",
            "size=1&size=2",
            "from=2026-10-12T00:00:00Z&until=2026-10-11T00:00:00Z",
        ).forEach {
            assertEquals(400, read("?$it").status, it)
        }
    }

    companion object {
        private var participantIds = emptyList<Long>()
        private var userStatus = 200
        private var userBody: String? = null
        private var batchBody: String? = null
        private val userRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        private var expoStatus = 200
        private var expoBody: String? = null
        private val expoRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val sources =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expos/") { exchange ->
                    assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    userRequests.add(exchange.requestURI.toString())
                    val query =
                        exchange.requestURI.rawQuery.split("&").associate {
                            val parts = it.split("=", limit = 2)
                            parts[0] to java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8)
                        }
                    val cursor = query.getValue("cursor").toLong()
                    val ids =
                        participantIds.filter {
                            it > cursor &&
                                when (query["q"]) {
                                    "missing" -> false
                                    "chosen" -> it == 2L
                                    else -> true
                                }
                        }
                    val next = if (ids.size > 500) ids[499].toString() else "null"
                    val body =
                        userBody ?: ids.take(500).joinToString(",", "{\"items\":[", "],\"nextCursor\":$next}") { id ->
                            """{"participantId":$id,"name":"Person $id","phoneNumber":null,"notificationPhoneNumber":"010-1234-5678","occupation":"TEACHER","representativeId":1,"linkedTraineeId":null,"applicationType":"PRE","information":{"answer":"current"},"surveyAnswer":null,"personalInformationStatus":true}"""
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(userStatus, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                createContext("/internal/standard-participants/details") { exchange ->
                    assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    val request = ObjectMapper().readTree(exchange.requestBody.bufferedReader().readText())
                    userRequests.add("batch:${request["expoId"].asString()}")
                    val ids = request["participantIds"]
                    val body = batchBody ?: (0 until ids.size()).joinToString(",", "[", "]") { "{\"participantId\":${ids[it].asLong()}}" }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(userStatus, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                createContext("/internal/expo/") { exchange ->
                    assertEquals("expo-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    val id = exchange.requestURI.path.split("/")[3]
                    val isAccess = exchange.requestURI.path.endsWith("/admin-access")
                    if (isAccess) expoRequests.add(exchange.requestHeaders.getFirst("Authorization"))
                    val body =
                        expoBody ?: if (isAccess) {
                            """{"expoId":"$id","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}"""
                        } else {
                            """{"expoId":"$id","items":[{"expoId":"$id","programType":"STANDARD","id":99,"title":"Empty session","startedAt":"2026-10-12T09:00","endedAt":"2026-10-12T10:00","groupId":"00000000-0000-0000-0000-000000000099","revision":1,"policy":null}],"page":0,"size":20,"totalElements":1,"definitionVersion":"version-one"}"""
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(expoStatus, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }
        private var attendanceStatus = 200
        private var attendanceBody: String? = null
        private val attendanceRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val attendance =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/program-attendances/summary") { exchange ->
                    assertEquals("POST", exchange.requestMethod)
                    assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    val request = exchange.requestBody.bufferedReader().readText()
                    attendanceRequests.add(request)
                    val refs = Regex("\\{\"programType\":\"(STANDARD|TRAINING)\",\"programId\":([0-9]+)\\}").findAll(request)
                    val body =
                        attendanceBody ?: refs.joinToString(",", "[", "]") {
                            val count = if (it.groupValues[1] == "STANDARD") 2 else 0
                            val times = if (count == 0) "null,\"lastEntryTime\":null" else "\"09:05\",\"lastEntryTime\":\"10:30\""
                            "${it.value.dropLast(1)},\"attendeeCount\":$count,\"firstEntryTime\":$times}"
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(attendanceStatus, if (attendanceStatus == 204) -1 else bytes.size.toLong())
                    if (attendanceStatus != 204) exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun attendanceProperties(registry: DynamicPropertyRegistry) {
            registry.add("application.attendance.url") { "http://127.0.0.1:${attendance.address.port}" }
            registry.add("application.attendance.internal-token") { "attendance-token" }
            registry.add("application.registration.user-url") { "http://127.0.0.1:${sources.address.port}" }
            registry.add("application.registration.user-internal-token") { "user-token" }
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${sources.address.port}" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
        }

        @AfterAll
        @JvmStatic
        fun stopAttendance() {
            attendance.stop(0)
            sources.stop(0)
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
