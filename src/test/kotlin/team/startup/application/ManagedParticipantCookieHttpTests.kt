package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import team.startup.application.domain.application.presentation.ManagedParticipantController
import team.startup.application.domain.application.presentation.ProgramSessionApplicationExceptionHandler
import team.startup.application.domain.application.presentation.dto.ParticipantQrCommand
import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsCommand
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsResponse
import team.startup.application.domain.application.service.GetParticipantQrService
import team.startup.application.domain.application.service.GetProgramParticipantsService
import team.startup.application.global.security.ProgramSessionSecurityConfig
import java.util.UUID

@WebMvcTest(
    controllers = [ManagedParticipantController::class],
    properties = ["application.program-sessions.enabled=true", "application.owner.session-cookie-name=OWNER_COOKIE"],
)
@Import(
    ProgramSessionSecurityConfig::class,
    ProgramSessionApplicationExceptionHandler::class,
    ManagedParticipantCookieHttpTests.Services::class,
)
class ManagedParticipantCookieHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Test
    fun `managed endpoints use existing owner cookie configuration and reject missing or ambiguous cookies`() {
        val expo = UUID.randomUUID()
        for (path in listOf(
            "/application/expos/$expo/program-sessions/STANDARD/1/participants",
            "/application/expos/$expo/participants/1/qr",
        )) {
            mvc.perform(get(path).cookie(Cookie("OWNER_COOKIE", "verified"))).andExpect(status().isOk)
            mvc.perform(get(path).cookie(Cookie("PARTICIPANT_SESSION", "verified"))).andExpect(status().isUnauthorized)
            mvc.perform(get(path).cookie(Cookie("OWNER_COOKIE", "a"), Cookie("OWNER_COOKIE", "b"))).andExpect(status().isUnauthorized)
        }
    }

    @TestConfiguration
    class Services {
        @Bean fun participants() =
            object : GetProgramParticipantsService {
                override fun execute(command: ProgramParticipantsCommand): ProgramParticipantsResponse {
                    assertEquals("verified", command.owner.sessionId)
                    return ProgramParticipantsResponse(false, emptyList())
                }
            }

        @Bean fun qr() =
            object : GetParticipantQrService {
                override fun execute(command: ParticipantQrCommand): ParticipantQrResponse {
                    assertEquals("verified", command.owner.sessionId)
                    return ParticipantQrResponse(command.participantId, "fixture-code")
                }
            }
    }
}
