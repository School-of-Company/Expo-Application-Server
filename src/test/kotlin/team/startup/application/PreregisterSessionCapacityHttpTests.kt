package team.startup.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.PreregisterSessionCapacityController
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityResponse
import team.startup.application.domain.application.service.GetPreregisterSessionCapacityService
import team.startup.application.global.security.InternalSecurityConfig
import team.startup.application.global.security.InternalTokenVerifier
import java.util.UUID

@WebMvcTest(
    PreregisterSessionCapacityController::class,
    properties = ["application.internal-token=test-token", "application.registration.enabled=false"],
)
@Import(InternalSecurityConfig::class, InternalTokenVerifier::class)
class PreregisterSessionCapacityHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @MockitoBean private lateinit var service: GetPreregisterSessionCapacityService

    private val expoId = UUID.fromString("0199c000-0000-7000-8000-000000000001")
    private val path = "/application/expos/$expoId/preregister-sessions/7/capacity"
    private val query = PreregisterSessionCapacityQuery(expoId, 7)

    @Test
    fun `등록 비활성화 상태에서도 로그인 없이 서비스 응답을 그대로 조회하고 캐시하지 않는다`() {
        `when`(service.execute(query)).thenReturn(PreregisterSessionCapacityResponse(7, 1, 2, 3))

        mvc
            .perform(get(path))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"sessionId":7,"remaining":1,"waitingRemaining":2,"maxApplicants":3}"""))
            .andExpect(header().string("Cache-Control", "no-store"))
        verify(service).execute(query)
    }

    @Test
    fun `없는 회차와 변경 중 회차와 공급자 장애는 서비스 오류 상태를 보존한다`() {
        for (code in listOf(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(ResponseStatusException(code)).`when`(service).execute(query)
            mvc.perform(get(path)).andExpect(status().`is`(code.value()))
        }
    }

    @Test
    fun `잘못된 박람회 ID와 양수가 아닌 회차 ID는 조회하지 않는다`() {
        listOf(
            path.replace(expoId.toString(), "invalid"),
            path.replace("/7/", "/0/"),
            path.replace("/7/", "/-1/"),
            path.replace("/7/", "/invalid/"),
        ).forEach { mvc.perform(get(it)).andExpect(status().isBadRequest) }
        verifyNoInteractions(service)
    }

    @Test
    fun `공개 허용은 정확한 GET 경로에만 적용하고 내부 조회는 토큰이 필요하다`() {
        mvc.perform(head(path)).andExpect(status().isUnauthorized)
        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized)
        mvc.perform(get(path.substringBeforeLast('/'))).andExpect(status().isUnauthorized)
        mvc.perform(get("/internal/program-applications/standard/7/participants/42")).andExpect(status().isUnauthorized)
        verifyNoInteractions(service)
    }
}
