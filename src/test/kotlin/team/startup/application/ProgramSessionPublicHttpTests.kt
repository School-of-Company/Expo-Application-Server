package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.ProgramSessionApplicationController
import team.startup.application.domain.application.presentation.ProgramSessionApplicationExceptionHandler
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.service.ApplyProgramSessionService
import team.startup.application.global.security.ProgramSessionSecurityConfig
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@WebMvcTest(
    controllers = [ProgramSessionApplicationController::class],
    properties = ["application.program-sessions.enabled=true", "application.owner.session-cookie-name=CUSTOM_PARTICIPANT_SESSION"],
)
@Import(
    ProgramSessionSecurityConfig::class,
    ProgramSessionApplicationExceptionHandler::class,
    ProgramSessionPublicHttpTests.Services::class,
)
class ProgramSessionPublicHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var service: RecordingService

    private lateinit var csrfCookie: Cookie
    private lateinit var header: String
    private lateinit var token: String
    private val expo = UUID.randomUUID()
    private val body = """{"participantId":1,"formVersion":1,"answers":{"101":"a"}}"""

    @BeforeEach
    fun setup() {
        service.commands.clear()
        val result = mvc.perform(get("/application/program-session-csrf")).andExpect(status().isOk).andReturn()
        val json = mapper.readTree(result.response.contentAsString)
        header = json.path("headerName").stringValue()
        token = json.path("token").stringValue()
        csrfCookie = requireNotNull(result.response.getCookie("XSRF-TOKEN"))
    }

    @Test
    fun `real cookie csrf roundtrip delegates only the participant cookie and server path`() {
        mvc
            .perform(request().header(header, token).cookie(csrfCookie, Cookie("CUSTOM_PARTICIPANT_SESSION", "verified")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
        val command = service.commands.single()
        assertEquals(expo.toString(), command.expoId)
        assertEquals("verified", command.sessionId)
        assertEquals("key", command.idempotencyKey)
        assertEquals(mapper.readTree("""{"101":"a"}"""), command.answers)
    }

    @Test
    fun `old cookie name is rejected even with valid csrf`() {
        mvc
            .perform(request().header(header, token).cookie(csrfCookie, Cookie("PARTICIPANT_SESSION", "verified")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
        assertEquals(0, service.commands.size)
    }

    @Test
    fun `csrf cookie ownership and request validation fail before service invocation`() {
        mvc
            .perform(request().cookie(Cookie("CUSTOM_PARTICIPANT_SESSION", "verified")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("CSRF_REQUIRED"))
        mvc
            .perform(request().header(header, "invalid").cookie(csrfCookie, Cookie("CUSTOM_PARTICIPANT_SESSION", "verified")))
            .andExpect(status().isForbidden)
        mvc
            .perform(request().header(header, token).cookie(csrfCookie).header("X-Internal-Token", "test-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
        mvc
            .perform(
                request()
                    .header(
                        header,
                        token,
                    ).cookie(csrfCookie, Cookie("CUSTOM_PARTICIPANT_SESSION", "a"), Cookie("CUSTOM_PARTICIPANT_SESSION", "b")),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(
                request()
                    .header(header, token)
                    .cookie(csrfCookie, Cookie("CUSTOM_PARTICIPANT_SESSION", "verified"))
                    .content(body.replace("\"participantId\":1", "\"participantId\":0")),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_APPLICATION"))
        assertEquals(0, service.commands.size)
    }

    @Test
    fun `session expiry and supplier failures use public errors without provider details`() {
        mapOf("expired" to (401 to "SESSION_EXPIRED"), "unavailable" to (503 to "SUPPLIER_UNAVAILABLE")).forEach { (session, expected) ->
            mvc
                .perform(request().header(header, token).cookie(csrfCookie, Cookie("CUSTOM_PARTICIPANT_SESSION", session)))
                .andExpect(status().`is`(expected.first))
                .andExpect(jsonPath("$.code").value(expected.second))
        }
    }

    private fun request() =
        post("/application/expos/$expo/program-sessions/STANDARD/1/applications")
            .header("Idempotency-Key", "key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    @TestConfiguration
    class Services {
        @Bean fun applicationService() = RecordingService()
    }

    class RecordingService : ApplyProgramSessionService {
        val commands = mutableListOf<ApplyProgramSessionCommand>()

        override fun execute(request: ApplyProgramSessionCommand): ProgramSessionReceipt {
            when (request.sessionId) {
                "expired" -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED")
                "unavailable" -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private supplier detail")
            }
            commands.add(request)
            return ProgramSessionReceipt(UUID.randomUUID(), request.programId, request.participantId, null)
        }
    }
}
