package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.NewFieldParticipant
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.impl.RegisterFieldVisitServiceImpl
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #333: 일행 0명 번호의 최초 현장등록을 실제 서비스·게이트웨이·PostgreSQL 원장과 가짜 Expo/Form/User/Attendance HTTP로 확인한다.
 * 가짜 User는 첫 신규 항목을 대표자로 만들고 같은 requestId는 같은 본문일 때만 처음 결과를 재현한다(User #202 계약).
 */
@Testcontainers(disabledWithoutDocker = true)
class FieldVisitInitialPartyTests {
    private val mapper = jacksonObjectMapper()
    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    private val phone = "01012345678"
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: RegisterFieldVisitServiceImpl
    private lateinit var visits: FieldVisitRegistrationRepository
    private lateinit var server: HttpServer
    private var expoDays = today to today
    private var failNextCreationAfterSave = false
    private val party = mutableListOf<Map<String, Any?>>()
    private val creations = mutableMapOf<String, Pair<JsonNode, String>>()
    private val creationBodies = mutableListOf<JsonNode>()
    private var nextId = 100L

    private val form =
        """
        {"id":"11111111-1111-1111-1111-111111111111","startDate":"2000-01-01T00:00:00+09:00","endDate":"2100-01-01T00:00:00+09:00",
         "dynamicForm":[
          {"id":1,"title":"직업","formType":"SELECT","jsonData":{},"otherJson":null,"dynamicFormType":"OCCUPATION","requiredStatus":true},
          {"id":2,"title":"지역","formType":"REGION","jsonData":{},"otherJson":null,"dynamicFormType":"DEFAULT","requiredStatus":true},
          {"id":3,"title":"학교","formType":"SENTENCE","jsonData":{},"otherJson":null,"dynamicFormType":"SCHOOL","requiredStatus":false},
          {"id":4,"title":"방문 목적","formType":"SENTENCE","jsonData":{},"otherJson":null,"dynamicFormType":"DEFAULT","requiredStatus":false},
          {"id":5,"title":"동행자","formType":"COMPANION","jsonData":{},"otherJson":null,"dynamicFormType":"COMPANION","requiredStatus":false}
         ]}
        """.trimIndent()

    @BeforeEach
    fun setup() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute("TRUNCATE tb_field_visit_registration, tb_field_visit_request CASCADE")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            val path = exchange.requestURI.path
            val sent = exchange.requestBody.bufferedReader().use { it.readText() }
            val (status, body) =
                synchronized(this) {
                    when {
                        path.endsWith("/participant-sessions/resolve") -> {
                            200 to """{"expoId":"${mapper.readTree(sent).path("expoId").stringValue()}","phoneNumber":"$phone",""" +
                                """"expiresAt":"2100-01-01T00:00:00"}"""
                        }

                        path.endsWith("/standard-participants/managed") -> {
                            200 to mapper.writeValueAsString(mapOf("registered" to party.isNotEmpty(), "participants" to party))
                        }

                        path.startsWith("/internal/forms/") -> {
                            200 to form
                        }

                        path.endsWith("/field-visit-participants") -> {
                            createParticipants(mapper.readTree(sent))
                        }

                        exchange.requestMethod == "PUT" -> {
                            204 to ""
                        }

                        path.startsWith("/internal/expo/") -> {
                            200 to """{"startedDay":"${expoDays.first}","finishedDay":"${expoDays.second}",""" +
                                """"participantPhoneVerificationRequired":true,"participantPhoneVerificationRevision":1}"""
                        }

                        else -> {
                            404 to "{}"
                        }
                    }
                }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        visits = FieldVisitRegistrationRepository(jdbc)
        val gateway =
            RegistrationGateway(
                mapper,
                url,
                url,
                url,
                "token",
                "token",
                "token",
                attendanceUrl = url,
                attendanceToken = "token",
                fieldVisitsEnabled = true,
                fieldVisits = visits,
            )
        service =
            RegisterFieldVisitServiceImpl(
                gateway,
                visits,
                FieldVisitRequestRepository(jdbc, mapper),
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                true,
                true,
                PreregisterSessionRepository(jdbc, mapper),
                mapper = mapper,
            )
    }

    @AfterEach
    fun stop() = server.stop(0)

    // 가짜 User #202: 대표자가 없으면 첫 항목이 대표자(전화번호 보유)이고, 있으면 그 아래에 더한다.
    private fun createParticipants(body: JsonNode): Pair<Int, String> {
        creationBodies.add(body)
        val requestId = body.path("requestId").stringValue()
        creations[requestId]?.let { (fingerprint, response) ->
            return if (fingerprint == body) 200 to response else 409 to "{}"
        }
        val result =
            body.path("participants").toList().map { person ->
                val id = nextId++
                party.add(
                    mapOf(
                        "participantId" to id,
                        "name" to person.path("name").stringValue(),
                        "occupation" to person.path("occupation").stringValue(),
                        "representative" to party.isEmpty(),
                        "applicationType" to "FIELD",
                    ),
                )
                mapOf("participantId" to id, "clientReference" to person.path("clientReference").stringValue())
            }
        creations[requestId] = body to mapper.writeValueAsString(result)
        if (failNextCreationAfterSave) {
            failNextCreationAfterSave = false
            return 500 to "{}"
        }
        return 200 to creations.getValue(requestId).second
    }

    private fun teacher(informationJson: String = """{"방문 목적":"수업 자료"}""") =
        NewFieldParticipant("rep", "김교사", "TEACHER", "광주초", "GWANGJU", informationJson)

    private fun companion(index: Int) = NewFieldParticipant("c$index", "학생$index", "ELEMENTARY_STUDENT", null, "JEONNAM")

    private fun register(
        request: RegisterFieldVisitRequest,
        key: String = "key-1",
    ) = service.execute(FieldVisitCommand(EXPO, "session", key, request))

    private fun status(action: () -> Unit) = assertThrows<ResponseStatusException> { action() }.statusCode.value()

    private fun storedRequests() = jdbc.queryForObject("SELECT count(*) FROM tb_field_visit_request", Int::class.java)

    @Test
    fun `일행 0명 번호의 교원 대표자와 초등 동행자는 한 요청으로 등록되고 대표자는 첫 신규 항목이다`() {
        val receipt = register(RegisterFieldVisitRequest(emptyList(), listOf(teacher(), companion(1)), true))
        assertEquals(today, receipt.visitDate)
        assertEquals(listOf("rep", "c1"), receipt.participants.map { it.clientReference })
        assertEquals(listOf("REGISTERED_FIELD", "REGISTERED_FIELD"), receipt.participants.map { it.outcome })
        assertTrue(receipt.participants.all { it.registration?.deliveryPending == false && it.registration.status == "ACTIVE" })
        val representative = party.single { it["representative"] == true }["participantId"]
        assertEquals(receipt.participants.first().participantId, representative)
        // 명단·명찰·엑셀용 원천: 사람별 프로필 답변과 커스텀 답변, 제출 당시 폼 ID·문항 snapshot이 User에 그대로 간다.
        val sent = creationBodies.single()
        assertEquals("11111111-1111-1111-1111-111111111111", sent.path("formId").stringValue())
        assertEquals(listOf("직업", "지역", "학교", "방문 목적", "동행자"), sent.path("questions").toList().map { it.path("title").stringValue() })
        val (repSent, companionSent) = sent.path("participants").toList()
        assertEquals("광주초", repSent.path("school").stringValue())
        val repAnswers = mapper.readTree(repSent.path("informationJson").stringValue())
        assertEquals("TEACHER", repAnswers.path("직업").stringValue())
        assertEquals("GWANGJU", repAnswers.path("지역").stringValue())
        assertEquals("광주초", repAnswers.path("학교").stringValue())
        assertEquals("수업 자료", repAnswers.path("방문 목적").stringValue())
        val companionAnswers = mapper.readTree(companionSent.path("informationJson").stringValue())
        assertEquals("ELEMENTARY_STUDENT", companionAnswers.path("직업").stringValue())
        assertEquals("JEONNAM", companionAnswers.path("지역").stringValue())
        assertTrue(companionAnswers.path("학교").isMissingNode)
    }

    @Test
    fun `대표자 단독과 5명 최초 등록은 받고 6명은 User 호출 전에 거절한다`() {
        assertEquals(1, register(RegisterFieldVisitRequest(emptyList(), listOf(teacher()), true)).participants.size)
        party.clear()
        jdbc.execute("TRUNCATE tb_field_visit_registration, tb_field_visit_request CASCADE")
        val five = listOf(teacher()) + (1..4).map(::companion)
        assertEquals(5, register(RegisterFieldVisitRequest(emptyList(), five, true), "key-5").participants.size)
        party.clear()
        val calls = creationBodies.size
        assertEquals(400, status { register(RegisterFieldVisitRequest(emptyList(), five + companion(5), true), "key-6") })
        assertEquals(calls, creationBodies.size)
    }

    @Test
    fun `프로필과 동의와 접수기간과 동적 문항 오류는 요청 저장과 User 생성 전에 거절한다`() {
        val invalid =
            listOf(
                RegisterFieldVisitRequest(emptyList(), listOf(teacher()), false),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher().copy(school = " ")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher().copy(occupation = "PRE_SERVICE_TEACHER", school = null)), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher().copy(occupation = "ASTRONAUT")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher().copy(region = "SEOUL")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher("[]")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher("""{"직업":"GENERAL"}""")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher("""{"지역":"SEOUL"}""")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher("""{"학교":"다른학교"}""")), true),
                RegisterFieldVisitRequest(emptyList(), listOf(teacher("""{"동행자":[{"name":"학생","occupation":"GENERAL"}]}""")), true),
            )
        invalid.forEachIndexed { index, request -> assertEquals(400, status { register(request, "bad-$index") }, "case $index") }
        expoDays = today.plusDays(1) to today.plusDays(1)
        assertEquals(400, status { register(RegisterFieldVisitRequest(emptyList(), listOf(teacher()), true), "early") })
        assertEquals(0, creationBodies.size)
        assertEquals(0, storedRequests())
    }

    @Test
    fun `빈 답변은 필수 프로필로 조립하고 일치하는 기존 답변과 커스텀 답변은 보존한다`() {
        val matching = """{"직업":"TEACHER","지역":"GWANGJU","학교":"광주초","방문 목적":"견학","동행자":[]}"""
        register(
            RegisterFieldVisitRequest(
                emptyList(),
                listOf(teacher("{}"), NewFieldParticipant("c1", "박교사", "TEACHER", "광주초", "GWANGJU", matching)),
                true,
            ),
        )
        val (repSent, second) = creationBodies.single().path("participants").toList()
        val assembled = mapper.readTree(repSent.path("informationJson").stringValue())
        assertEquals(
            mapOf("직업" to "TEACHER", "지역" to "GWANGJU", "학교" to "광주초"),
            assembled.properties().associate {
                it.key to
                    it.value.stringValue()
            },
        )
        assertEquals(matching, second.path("informationJson").stringValue())
    }

    @Test
    fun `기존 일행에 동행자를 더하면 대표자는 유지되고 새 사람만 생성한다`() {
        val first = register(RegisterFieldVisitRequest(emptyList(), listOf(teacher(), companion(1)), true))
        val ids = first.participants.map { it.participantId }
        val added = register(RegisterFieldVisitRequest(ids, listOf(companion(2)), true), "key-add")
        assertEquals(listOf("REUSED_FIELD", "REUSED_FIELD", "REGISTERED_FIELD"), added.participants.map { it.outcome })
        assertEquals(ids.first(), party.single { it["representative"] == true }["participantId"])
        assertEquals(
            listOf("c2"),
            creationBodies
                .last()
                .path("participants")
                .toList()
                .map { it.path("clientReference").stringValue() },
        )
        assertEquals(409, status { register(RegisterFieldVisitRequest(emptyList(), (3..5).map(::companion), true), "key-over") })
    }

    @Test
    fun `같은 키 재시도는 처음 결과를 재현하고 다른 내용은 거절하며 응답 유실은 같은 User 본문으로 복구한다`() {
        val request = RegisterFieldVisitRequest(emptyList(), listOf(teacher("{}"), companion(1)), true)
        failNextCreationAfterSave = true
        assertTrue(status { register(request) } >= 500)
        val receipt = register(request)
        assertEquals(2, creationBodies.size)
        assertEquals(creationBodies[0], creationBodies[1])
        assertEquals(2, party.size)
        assertEquals(receipt, register(request))
        assertEquals(2, creationBodies.size)
        assertEquals(409, status { register(request.copy(newParticipants = listOf(teacher())), "key-1") })
    }

    @Test
    fun `같은 키 동시 최초 신청은 한 일행만 만든다`() {
        val request = RegisterFieldVisitRequest(emptyList(), listOf(teacher(), companion(1)), true)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results =
                (1..2).map {
                    executor.submit<Int> {
                        start.await(10, TimeUnit.SECONDS)
                        try {
                            register(request).participants.size
                        } catch (ex: ResponseStatusException) {
                            ex.statusCode.value()
                        }
                    }
                }
            start.countDown()
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(2, party.size, "$outcomes")
            assertTrue(outcomes.contains(2) && outcomes.all { it == 2 || it == 409 }, "$outcomes")
            assertEquals(2, visits.findByParticipants(EXPO, party.map { it["participantId"] as Long }).size)
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private val EXPO = UUID.fromString("22222222-2222-2222-2222-222222222222")

        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
