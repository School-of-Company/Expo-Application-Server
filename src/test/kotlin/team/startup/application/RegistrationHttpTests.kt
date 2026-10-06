package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.service.impl.ExpoPeriod
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import team.startup.application.domain.application.service.impl.ParticipantRegistrationResult
import team.startup.application.domain.application.service.impl.RegistrationField
import team.startup.application.domain.application.service.impl.RegistrationForm
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.RegistrationQuestion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(
    properties = ["eureka.client.enabled=false", "application.registration.enabled=true", "management.health.redis.enabled=false"],
)
@AutoConfigureMockMvc
@Testcontainers
class RegistrationHttpTests {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var gateway: RegistrationGateway

    @Test
    fun `사전 일반 등록은 폼 답변을 분리하고 인원을 집계하며 재시도 키를 전달한다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_1)).thenReturn(ExpoPeriod(today.minusDays(1).toString(), today.plusDays(1).toString()))
        `when`(gateway.form(EXPO_1, "STANDARD", "PRE", true)).thenReturn(
            registrationForm(
                OffsetDateTime.now().minusDays(1).toString(),
                OffsetDateTime.now().plusDays(1).toString(),
                listOf(field(1, "직업", "OCCUPATION"), field(2, "학교", "SCHOOL")),
            ),
        )
        val answers = mapper.writeValueAsString(mapOf("직업" to "TEACHER", "학교" to "광주고"))
        val body = body(answers)
        val questions =
            listOf(
                RegistrationQuestion(1, "직업", 0, "DROPDOWN", mapper.readTree("{\"TEACHER\":\"교사\"}"), null, "OCCUPATION"),
                RegistrationQuestion(
                    2,
                    "학교",
                    1,
                    "SENTENCE",
                    mapper.readTree("{}"),
                    mapper.readTree("{\"conditional\":{\"parentIndex\":0}}"),
                    "SCHOOL",
                ),
            )
        val registration =
            ParticipantRegistration(
                EXPO_1,
                "홍길동",
                "01012345678",
                answers,
                true,
                "PRE",
                null,
                "TEACHER",
                "광주고",
                "request-1",
                FORM_ID,
                questions,
            )
        `when`(gateway.participant("STANDARD", registration)).thenReturn(ParticipantRegistrationResult(42, "01012345678"))
        mockMvc
            .perform(
                post("/application/pre-standard/$EXPO_1")
                    .header("Idempotency-Key", "request-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
        verify(gateway).participant("STANDARD", registration)
        verify(gateway).countStandard(EXPO_1, 42)
    }

    @Test
    fun `현장 연수자 등록은 폼 없이 FIELD로 저장하고 인원을 집계하지 않는다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_2)).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        `when`(gateway.form(EXPO_2, "TRAINEE", "FIELD", false)).thenReturn(null)
        val registration =
            ParticipantRegistration(EXPO_2, "홍길동", "01012345678", "{}", true, "FIELD", "training-1", null, null, "request-2", null, null)
        `when`(gateway.participant("TRAINEE", registration)).thenReturn(ParticipantRegistrationResult(7, "01012345678"))
        mockMvc
            .perform(
                post("/application/field/$EXPO_2")
                    .header("Idempotency-Key", "request-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{}", "training-1")),
            ).andExpect(status().isCreated)
        verify(gateway).participant("TRAINEE", registration)
        verify(gateway).expoPeriod(EXPO_2)
        verify(gateway).form(EXPO_2, "TRAINEE", "FIELD", false)
        verifyNoMoreInteractions(gateway)
    }

    @Test
    fun `학교가 필요한 직업에 학교 답변이 없으면 참가자를 만들지 않는다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_3)).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        `when`(gateway.form(EXPO_3, "STANDARD", "PRE", true)).thenReturn(
            registrationForm(
                OffsetDateTime.now().minusDays(1).toString(),
                OffsetDateTime.now().plusDays(1).toString(),
                listOf(field(1, "직업", "OCCUPATION"), field(2, "학교", "SCHOOL")),
            ),
        )
        mockMvc
            .perform(
                post("/application/pre-standard/$EXPO_3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"직업\":\"TEACHER\"}")),
            ).andExpect(status().isBadRequest)
        verify(gateway).expoPeriod(EXPO_3)
        verify(gateway).form(EXPO_3, "STANDARD", "PRE", true)
        verifyNoMoreInteractions(gateway)
    }

    @Test
    fun `학교 답변이 문자열이 아니면 참가자를 만들지 않는다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_4)).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        `when`(gateway.form(EXPO_4, "STANDARD", "PRE", true)).thenReturn(
            registrationForm(
                OffsetDateTime.now().minusDays(1).toString(),
                OffsetDateTime.now().plusDays(1).toString(),
                listOf(field(1, "직업", "OCCUPATION"), field(2, "학교", "SCHOOL")),
            ),
        )
        mockMvc
            .perform(
                post("/application/pre-standard/$EXPO_4")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"직업\":\"TEACHER\",\"학교\":123}")),
            ).andExpect(status().isBadRequest)
        verify(gateway).expoPeriod(EXPO_4)
        verify(gateway).form(EXPO_4, "STANDARD", "PRE", true)
        verifyNoMoreInteractions(gateway)
    }

    @Test
    fun `참가자 저장 후 집계 실패는 재시도 가능한 503으로 반환한다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/expo/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val actualGateway =
                RegistrationGateway(mapper, "http://127.0.0.1:${server.address.port}", "", "", "test-token")
            val error = assertThrows<ResponseStatusException> { actualGateway.countStandard(EXPO_1, 42) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `현장 폼 404는 허용하고 연수자 응답의 traineeId를 읽는다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sentBody = AtomicReference<String>()
        server.createContext("/internal/forms/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/internal/trainees") { exchange ->
            sentBody.set(exchange.requestBody.bufferedReader().readText())
            val response = "{\"traineeId\":7,\"phoneNumber\":\"01012345678\"}".toByteArray()
            exchange.sendResponseHeaders(201, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val actualGateway = RegistrationGateway(mapper, "", url, url, "test-token")
            assertNull(actualGateway.form(EXPO_1, "TRAINEE", "FIELD", false))
            assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows<ResponseStatusException> { actualGateway.form(EXPO_1, "TRAINEE", "PRE", true) }.statusCode,
            )
            val registration =
                ParticipantRegistration(
                    EXPO_1,
                    "홍길동",
                    "01012345678",
                    "{}",
                    true,
                    "FIELD",
                    "training-1",
                    null,
                    null,
                    "request-1",
                    null,
                    null,
                )
            assertEquals(7, actualGateway.participant("TRAINEE", registration).participantId)
            val sent = mapper.readTree(sentBody.get())
            assertEquals(mapper.readTree("null"), sent.get("formId"))
            assertEquals(mapper.readTree("null"), sent.get("questions"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `현장 등록에 폼이 있으면 기간과 무관하게 스냅샷을 전달한다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_2)).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        `when`(gateway.form(EXPO_2, "TRAINEE", "FIELD", false)).thenReturn(
            registrationForm("2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z", listOf(field(3, "학교", "SCHOOL"))),
        )
        val registration =
            ParticipantRegistration(
                EXPO_2,
                "홍길동",
                "01012345678",
                "{\"학교\":\"광주고\"}",
                true,
                "FIELD",
                "training-1",
                null,
                "광주고",
                "request-2",
                FORM_ID,
                listOf(
                    RegistrationQuestion(
                        3,
                        "학교",
                        0,
                        "SENTENCE",
                        mapper.readTree("{}"),
                        mapper.readTree("{\"conditional\":{\"parentIndex\":0}}"),
                        "SCHOOL",
                    ),
                ),
            )
        `when`(gateway.participant("TRAINEE", registration)).thenReturn(ParticipantRegistrationResult(7, "01012345678"))
        mockMvc
            .perform(
                post("/application/field/$EXPO_2")
                    .header("Idempotency-Key", "request-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"학교\":\"광주고\"}", "training-1")),
            ).andExpect(status().isCreated)
        verify(gateway).participant("TRAINEE", registration)
    }

    @Test
    fun `실제 Form 응답의 문항 스펙을 User 요청 JSON에 보존한다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sentBody = AtomicReference<String>()
        server.createContext("/internal/forms/") { exchange ->
            val response =
                """{"id":"$FORM_ID","expoId":"$EXPO_1","title":"신청서","informationText":"안내","participantType":"STANDARD","applicationType":"FIELD","startDate":"2026-10-01T00:00:00Z","endDate":"2026-10-31T00:00:00Z","dynamicForm":[{"id":3,"title":"학교","formType":"SENTENCE","requiredStatus":false,"jsonData":{},"otherJson":{"conditional":{"parentIndex":0}},"dynamicFormType":"SCHOOL"}]}"""
                    .toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/internal/standard-participants") { exchange ->
            sentBody.set(exchange.requestBody.bufferedReader().readText())
            val response = "{\"participantId\":42,\"phoneNumber\":\"01012345678\"}".toByteArray()
            exchange.sendResponseHeaders(201, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val actualGateway = RegistrationGateway(mapper, "", url, url, "test-token")
            val form = actualGateway.form(EXPO_1, "STANDARD", "FIELD", false)!!
            val field = form.dynamicForm.single()
            val question =
                RegistrationQuestion(field.id, field.title, 0, field.formType, field.jsonData, field.otherJson, field.dynamicFormType)
            val registration =
                ParticipantRegistration(
                    EXPO_1,
                    "홍길동",
                    "01012345678",
                    "{}",
                    true,
                    "FIELD",
                    null,
                    null,
                    null,
                    "request-1",
                    form.id,
                    listOf(question),
                )
            actualGateway.participant("STANDARD", registration)
            val sent = mapper.readTree(sentBody.get())
            assertEquals(FORM_ID, sent.get("formId").stringValue())
            assertEquals(
                mapper.readTree(
                    """[{"id":3,"title":"학교","order":0,"formType":"SENTENCE","jsonData":{},"otherJson":{"conditional":{"parentIndex":0}},"dynamicFormType":"SCHOOL"}]""",
                ),
                sent.get("questions"),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `등록 경로 외 요청은 공개하지 않고 잘못된 박람회 ID는 거부한다`() {
        mockMvc.perform(get("/application/$EXPO_1")).andExpect(status().isUnauthorized)
        mockMvc
            .perform(post("/application/pre-standard/invalid-id").contentType(MediaType.APPLICATION_JSON).content(body("{}")))
            .andExpect(status().isBadRequest)
        verifyNoMoreInteractions(gateway)
    }

    @Test
    fun `박람회 날짜 응답이 잘못되면 502를 반환한다`() {
        `when`(gateway.expoPeriod(EXPO_1)).thenReturn(ExpoPeriod("invalid", "2026-12-31"))
        mockMvc
            .perform(post("/application/field/standard/$EXPO_1").contentType(MediaType.APPLICATION_JSON).content(body("{}")))
            .andExpect(status().isBadGateway)
    }

    @Test
    fun `폼 날짜 응답이 잘못되면 502를 반환한다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_1)).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        `when`(gateway.form(EXPO_1, "STANDARD", "PRE", true)).thenReturn(registrationForm("invalid", "2026-12-31T00:00:00Z"))
        mockMvc
            .perform(post("/application/pre-standard/$EXPO_1").contentType(MediaType.APPLICATION_JSON).content(body("{}")))
            .andExpect(status().isBadGateway)
    }

    @Test
    fun `대문자 박람회 ID는 정규화해서 하위 서비스에 전달한다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        `when`(gateway.expoPeriod(EXPO_1)).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        `when`(gateway.form(EXPO_1, "STANDARD", "FIELD", false)).thenReturn(null)
        val registration =
            ParticipantRegistration(EXPO_1, "홍길동", "01012345678", "{}", true, "FIELD", null, null, null, "request-1", null, null)
        `when`(gateway.participant("STANDARD", registration)).thenReturn(ParticipantRegistrationResult(42, "01012345678"))
        mockMvc
            .perform(
                post("/application/field/standard/${EXPO_1.uppercase()}")
                    .header("Idempotency-Key", "request-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{}")),
            ).andExpect(status().isCreated)
        verify(gateway).countStandard(EXPO_1, 42)
    }

    private fun body(
        answers: String,
        trainingId: String? = null,
    ) = mapper.writeValueAsString(
        mapOf(
            "name" to "홍길동",
            "phoneNumber" to "01012345678",
            "informationJson" to answers,
            "personalInformationStatus" to true,
            "trainingId" to trainingId,
        ),
    )

    private fun registrationForm(
        startDate: String,
        endDate: String,
        dynamicForm: List<RegistrationField> = emptyList(),
    ) = RegistrationForm(FORM_ID, startDate, endDate, dynamicForm)

    private fun field(
        id: Long,
        title: String,
        type: String,
    ) = RegistrationField(
        id,
        title,
        if (type == "SCHOOL") "SENTENCE" else "DROPDOWN",
        mapper.readTree(if (type == "OCCUPATION") "{\"TEACHER\":\"교사\"}" else "{}"),
        if (type == "SCHOOL") mapper.readTree("{\"conditional\":{\"parentIndex\":0}}") else null,
        type,
    )

    companion object {
        private const val FORM_ID = "0199c000-0000-7000-8000-000000000010"
        private const val EXPO_1 = "0199c000-0000-7000-8000-000000000001"
        private const val EXPO_2 = "0199c000-0000-7000-8000-000000000002"
        private const val EXPO_3 = "0199c000-0000-7000-8000-000000000003"
        private const val EXPO_4 = "0199c000-0000-7000-8000-000000000004"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
