package team.startup.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import team.startup.application.domain.application.presentation.ProgramGuardianPolicyController
import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand
import team.startup.application.domain.application.presentation.dto.ProgramChangePayload
import team.startup.application.domain.application.presentation.dto.ProgramMutation
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.PrepareProgramChangeService
import team.startup.application.global.security.InternalSecurityConfig
import team.startup.application.global.security.InternalTokenVerifier
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@WebMvcTest(controllers = [ProgramGuardianPolicyController::class])
@Import(InternalSecurityConfig::class, InternalTokenVerifier::class)
class ProgramGuardianPolicyHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var prepare: PrepareProgramChangeService
    private val expo = UUID.randomUUID()

    @Test
    fun `Expo capability probe requires internal token and returns version one`() {
        val path = "/internal/expos/$expo/program-policy-capabilities"
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(get(path).header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized)
        mvc
            .perform(get(path).header("X-Internal-Token", "test-token"))
            .andExpect(status().isNoContent)
            .andExpect(header().string("X-Program-Guardian-Policy-Version", "1"))
        verifyNoInteractions(prepare)
    }

    @Test
    fun `guardian policy prepare delegates the same change protocol and returns support header`() {
        val change = UUID.randomUUID()
        val old = ProgramSessionDefinition(expo.toString(), ProgramSessionType.STANDARD, 1, UUID.randomUUID(), 1, null)
        val payload = ProgramChangePayload(listOf(ProgramMutation(old, null)))
        mvc
            .perform(
                put("/internal/expos/$expo/program-changes/$change/guardian-policy")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(payload)),
            ).andExpect(status().isNoContent)
            .andExpect(header().string("X-Program-Guardian-Policy-Version", "1"))
        verify(prepare).execute(PrepareProgramChangeCommand(expo, change, payload))
    }

    @Test
    fun `empty changes are rejected before prepare`() {
        mvc
            .perform(
                put("/internal/expos/$expo/program-changes/${UUID.randomUUID()}/guardian-policy")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[]}"""),
            ).andExpect(status().isBadRequest)
        verifyNoInteractions(prepare)
    }
}
