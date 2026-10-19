package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.MethodMode
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.OwnerApplicationController
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.service.AddOwnerPreregisterCompanionsService
import team.startup.application.domain.application.service.AddOwnerPreregisterSessionService
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.GetOwnerApplicationsService
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistService
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistsService
import team.startup.application.domain.application.service.WithdrawOwnerPreregisterWaitlistService
import team.startup.application.domain.application.service.impl.ParticipantSessionException
import team.startup.application.global.security.InternalSecurityConfig
import team.startup.application.global.security.InternalTokenVerifier
import java.util.UUID

@WebMvcTest(OwnerApplicationController::class)
@Import(InternalSecurityConfig::class, InternalTokenVerifier::class)
@TestPropertySource(properties = ["application.internal-token=test-token"])
class OwnerApplicationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @MockitoBean private lateinit var query: GetOwnerApplicationsService

    @MockitoBean private lateinit var cancel: CancelOwnerApplicationService

    @MockitoBean private lateinit var waitlist: GetOwnerPreregisterWaitlistService

    @MockitoBean private lateinit var waitlists: GetOwnerPreregisterWaitlistsService

    @MockitoBean private lateinit var withdraw: WithdrawOwnerPreregisterWaitlistService

    @MockitoBean private lateinit var addCompanions: AddOwnerPreregisterCompanionsService

    @MockitoBean private lateinit var addSession: AddOwnerPreregisterSessionService

    @Test
    fun `cookie is required and internal token or query session cannot replace it`() {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized)
        mvc.perform(get(PATH).header("X-Internal-Token", "test-token")).andExpect(status().isUnauthorized)
        mvc.perform(get(PATH).param("sessionId", "owner")).andExpect(status().isUnauthorized)
        mvc.perform(get(PATH).cookie(Cookie("PARTICIPANT_SESSION", ""))).andExpect(status().isUnauthorized)
        mvc
            .perform(get(PATH).cookie(Cookie("PARTICIPANT_SESSION", "owner"), Cookie("PARTICIPANT_SESSION", "other")))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(query, cancel)
    }

    @Test
    fun `missing cookie answers SESSION_REQUIRED code on every owner path`() {
        val paths =
            listOf(
                get(PATH),
                post("$PATH/PREREGISTER/2/cancellation").with(csrf()),
                get("$PATH/preregister-waitlist"),
                post("$PATH/preregister-waitlist/cancellation").with(csrf()),
                post("$PATH/PREREGISTER/2/companions")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"companions":[{"name":"a","occupation":"STUDENT","region":"SEOUL"}]}"""),
                post("$PATH/preregister-sessions")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"sessionId":2,"participantIds":[1]}"""),
            )
        paths.forEach {
            mvc
                .perform(it)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
        }
        verifyNoInteractions(query, cancel, waitlist, withdraw, addCompanions, addSession)
    }

    @Test
    fun `session code from the gateway is passed through on query cancel waitlist and withdraw`() {
        for (code in listOf("SESSION_EXPIRED", "SESSION_REQUIRED")) {
            val session = Cookie("PARTICIPANT_SESSION", code)
            `when`(query.execute(GetOwnerApplicationsCommand(EXPO, code))).thenThrow(ParticipantSessionException(code))
            doThrow(ParticipantSessionException(code)).`when`(cancel).execute(COMMAND.copy(sessionId = code))
            `when`(waitlist.execute(OwnerPreregisterWaitlistCommand(EXPO, code))).thenThrow(ParticipantSessionException(code))
            doThrow(ParticipantSessionException(code)).`when`(withdraw).execute(OwnerPreregisterWaitlistCommand(EXPO, code))
            listOf(
                get(PATH).cookie(session),
                post("$PATH/PREREGISTER/2/cancellation").with(csrf()).cookie(session),
                get("$PATH/preregister-waitlist").cookie(session),
                post("$PATH/preregister-waitlist/cancellation").with(csrf()).cookie(session),
            ).forEach {
                mvc
                    .perform(it)
                    .andExpect(status().isUnauthorized)
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value(code))
            }
        }
    }

    @Test
    fun `non session failures keep their status without a session code`() {
        `when`(query.execute(GetOwnerApplicationsCommand(EXPO, "bad")))
            .thenThrow(ResponseStatusException(HttpStatus.BAD_GATEWAY))
        mvc
            .perform(get(PATH).cookie(Cookie("PARTICIPANT_SESSION", "bad")))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").doesNotExist())
    }

    @Test
    // csrf() swaps the shared filter's repository for a session-backed test one.
    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    fun `GET token and session authorize POST without exposing participant session`() {
        `when`(query.execute(GetOwnerApplicationsCommand(EXPO, "owner")))
            .thenReturn(OwnerApplicationsResponse(emptyList(), emptyList()))
        val result =
            mvc
                .perform(get(PATH).cookie(Cookie("PARTICIPANT_SESSION", "owner")))
                .andExpect(status().isOk)
                .andReturn()
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
        assertNull(result.request.getSession(false))
        val csrfCookie = requireNotNull(result.response.getCookie("XSRF-TOKEN"))
        val token = requireNotNull(result.response.getHeader("X-CSRF-TOKEN"))
        // A fresh session stands in for another instance or a restarted one.
        mvc
            .perform(
                post("$PATH/PREREGISTER/2/cancellation")
                    .session(MockHttpSession())
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"), csrfCookie),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post("$PATH/PREREGISTER/2/cancellation")
                    .session(MockHttpSession())
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"))
                    .header("X-CSRF-TOKEN", token),
            ).andExpect(status().isForbidden)
        verifyNoInteractions(cancel)
        mvc
            .perform(
                post("$PATH/PREREGISTER/2/cancellation")
                    .session(MockHttpSession())
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"), csrfCookie)
                    .header("X-CSRF-TOKEN", token),
            ).andExpect(status().isNoContent)
        verify(cancel).execute(COMMAND)
    }

    @Test
    fun `missing or invalid CSRF blocks POST before services`() {
        mvc
            .perform(post("$PATH/PREREGISTER/2/cancellation").cookie(Cookie("PARTICIPANT_SESSION", "owner")))
            .andExpect(status().isForbidden)
        mvc
            .perform(post("$PATH/PREREGISTER/2/cancellation").with(csrf().useInvalidToken()).cookie(Cookie("PARTICIPANT_SESSION", "owner")))
            .andExpect(status().isForbidden)
        mvc.perform(post("$PATH/PREREGISTER/2/cancellation").with(csrf())).andExpect(status().isUnauthorized)
        verifyNoInteractions(query, cancel)
    }

    @Test
    fun `owner failures and unavailable cancellation guard keep their status`() {
        `when`(query.execute(GetOwnerApplicationsCommand(EXPO, "expired")))
            .thenThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        mvc.perform(get(PATH).cookie(Cookie("PARTICIPANT_SESSION", "expired"))).andExpect(status().isUnauthorized)
        doThrow(ResponseStatusException(HttpStatus.NOT_FOUND)).`when`(cancel).execute(COMMAND.copy(sessionId = "other"))
        mvc
            .perform(post("$PATH/PREREGISTER/2/cancellation").with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "other")))
            .andExpect(status().isNotFound)
        doThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).`when`(cancel).execute(COMMAND)
        mvc
            .perform(post("$PATH/PREREGISTER/2/cancellation").with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "owner")))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    // csrf() swaps the shared filter's repository for a session-backed test one.
    @DirtiesContext(methodMode = MethodMode.BEFORE_METHOD)
    fun `waitlist lookup and withdrawal require the participant session and CSRF`() {
        val owner = OwnerPreregisterWaitlistCommand(EXPO, "owner")
        mvc.perform(get("$PATH/preregister-waitlist")).andExpect(status().isUnauthorized)
        mvc
            .perform(post("$PATH/preregister-waitlist/cancellation").cookie(Cookie("PARTICIPANT_SESSION", "owner")))
            .andExpect(status().isForbidden)
        verifyNoInteractions(waitlist, withdraw)
        `when`(waitlist.execute(owner)).thenReturn(OwnerPreregisterWaitlist(1, 3, "WAITING", 2))
        val result =
            mvc
                .perform(get("$PATH/preregister-waitlist").cookie(Cookie("PARTICIPANT_SESSION", "owner")))
                .andExpect(status().isOk)
                .andReturn()
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
        assertEquals(
            tools.jackson.module.kotlin.jacksonObjectMapper().readTree(
                """{"sessionId":1,"applicants":3,"status":"WAITING","position":2}""",
            ),
            tools.jackson.module.kotlin
                .jacksonObjectMapper()
                .readTree(result.response.contentAsString),
        )
        mvc
            .perform(
                post("$PATH/preregister-waitlist/cancellation")
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"), requireNotNull(result.response.getCookie("XSRF-TOKEN")))
                    .session(MockHttpSession())
                    .header("X-CSRF-TOKEN", requireNotNull(result.response.getHeader("X-CSRF-TOKEN"))),
            ).andExpect(status().isNoContent)
        verify(withdraw).execute(owner)
        mvc.perform(post("$PATH/preregister-waitlist/cancellation").with(csrf())).andExpect(status().isUnauthorized)
        verifyNoInteractions(query, cancel)
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val PATH = "/application/expos/$EXPO/my-applications"
        private val COMMAND = CancelOwnerApplicationCommand(EXPO, "owner", OwnerApplicationType.PREREGISTER, "2")
    }
}
