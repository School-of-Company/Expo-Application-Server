package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.OwnerApplicationController
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.GetOwnerApplicationsService
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
    fun `GET token and session authorize POST without exposing participant session`() {
        `when`(query.execute(GetOwnerApplicationsCommand(EXPO, "owner")))
            .thenReturn(OwnerApplicationsResponse(emptyList(), emptyList()))
        val result =
            mvc
                .perform(get(PATH).cookie(Cookie("PARTICIPANT_SESSION", "owner")))
                .andExpect(status().isOk)
                .andReturn()
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
        mvc
            .perform(
                post("$PATH/PREREGISTER/2/cancellation")
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"))
                    .session(result.request.session as MockHttpSession)
                    .header("X-CSRF-TOKEN", requireNotNull(result.response.getHeader("X-CSRF-TOKEN"))),
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

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val PATH = "/application/expos/$EXPO/my-applications"
        private val COMMAND = CancelOwnerApplicationCommand(EXPO, "owner", OwnerApplicationType.PREREGISTER, "2")
    }
}
