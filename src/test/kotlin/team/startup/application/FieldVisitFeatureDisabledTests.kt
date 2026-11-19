package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import team.startup.application.domain.application.presentation.FieldVisitController
import team.startup.application.domain.application.presentation.RegistrationBadRequestHandler
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.service.GetFieldVisitPartyService
import team.startup.application.domain.application.service.RegisterFieldVisitService
import team.startup.application.domain.application.service.impl.FeatureDisabledException
import java.util.UUID

// #418: 방문일 등록의 기능 OFF는 다른 409와 구분되게 code FEATURE_DISABLED를 싣는다.
class FieldVisitFeatureDisabledTests {
    @Test
    fun `field visit OFF returns 409 with FEATURE_DISABLED code`() {
        val register = mock(RegisterFieldVisitService::class.java)
        `when`(register.execute(any(FieldVisitCommand::class.java) ?: placeholder())).thenThrow(FeatureDisabledException())
        val mvc =
            MockMvcBuilders
                .standaloneSetup(FieldVisitController(mock(GetFieldVisitPartyService::class.java), register, "PARTICIPANT_SESSION"))
                .setControllerAdvice(RegistrationBadRequestHandler())
                .build()
        mvc
            .perform(
                post("/application/expos/${UUID.randomUUID()}/field-visits")
                    .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participantIds":[1],"personalInformationStatus":true}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"))
    }

    private fun placeholder() =
        FieldVisitCommand(
            UUID.randomUUID(),
            "",
            "",
            team.startup.application.domain.application.presentation.dto
                .RegisterFieldVisitRequest(emptyList(), emptyList(), true),
        )
}
