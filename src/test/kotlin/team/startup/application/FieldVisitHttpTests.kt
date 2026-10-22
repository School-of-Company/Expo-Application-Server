package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import team.startup.application.domain.application.presentation.FieldVisitController
import team.startup.application.domain.application.presentation.InternalFieldVisitController
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitParty
import team.startup.application.domain.application.presentation.dto.FieldVisitReceipt
import team.startup.application.domain.application.presentation.dto.FieldVisitSourceCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitSourcePage
import team.startup.application.domain.application.presentation.dto.FieldVisitSourceQuery
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.service.GetFieldVisitPartyService
import team.startup.application.domain.application.service.GetFieldVisitSourceService
import team.startup.application.domain.application.service.RegisterFieldVisitService
import team.startup.application.global.security.InternalSecurityConfig
import team.startup.application.global.security.InternalTokenVerifier
import java.time.LocalDate
import java.util.UUID

@WebMvcTest(FieldVisitController::class, InternalFieldVisitController::class)
@Import(InternalSecurityConfig::class, InternalTokenVerifier::class)
@TestPropertySource(properties = ["application.internal-token=test-token", "application.registration.enabled=true"])
class FieldVisitHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @MockitoBean private lateinit var query: GetFieldVisitPartyService

    @MockitoBean private lateinit var register: RegisterFieldVisitService

    @MockitoBean private lateinit var source: GetFieldVisitSourceService
    private val expoId = UUID.randomUUID()
    private val path get() = "/application/expos/$expoId/field-visits"
    private val body = """{"participantIds":[1],"personalInformationStatus":true}"""

    @Test
    fun `방문 등록은 세션과 CSRF 및 멱등키가 필수다`() {
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(get(path).header("X-Internal-Token", "test-token")).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(
                    path,
                ).cookie(
                    Cookie("PARTICIPANT_SESSION", "owner"),
                ).header("Idempotency-Key", "key")
                    .contentType("application/json")
                    .content(body),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path)
                    .with(csrf())
                    .header("Idempotency-Key", "key")
                    .contentType("application/json")
                    .content(body),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path)
                    .with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner"))
                    .contentType("application/json")
                    .content(body),
            ).andExpect(status().isBadRequest)
        verifyNoInteractions(query, register)
    }

    @Test
    fun `조회는 no-store와 csrf토큰을 제공하고 등록은 참가자 결과를 반환한다`() {
        val day = LocalDate.of(2026, 10, 22)
        `when`(query.execute(GetOwnerApplicationsCommand(expoId, "owner"))).thenReturn(FieldVisitParty(day, emptyList()))
        `when`(register.execute(FieldVisitCommand(expoId, "owner", "key", RegisterFieldVisitRequest(listOf(1), emptyList(), true))))
            .thenReturn(FieldVisitReceipt(day, emptyList()))
        mvc
            .perform(get(path).cookie(Cookie("PARTICIPANT_SESSION", "owner")))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("X-CSRF-TOKEN"))
        mvc
            .perform(
                post(
                    path,
                ).with(
                    csrf(),
                ).cookie(Cookie("PARTICIPANT_SESSION", "owner"))
                    .header("Idempotency-Key", "key")
                    .contentType("application/json")
                    .content(body),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `원천은 내부 토큰을 요구하고 페이지 상한을 검증한다`() {
        val internal = "/internal/expos/$expoId/field-visits"
        mvc.perform(get(internal)).andExpect(status().isUnauthorized)
        mvc.perform(get(internal).header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized)
        mvc.perform(get(internal).header("X-Internal-Token", "test-token").param("size", "501")).andExpect(status().isBadRequest)
        `when`(
            source.execute(FieldVisitSourceCommand(expoId, FieldVisitSourceQuery())),
        ).thenReturn(FieldVisitSourcePage(emptyList(), emptyList(), null))
        mvc
            .perform(
                get(internal).header("X-Internal-Token", "test-token"),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
    }
}
