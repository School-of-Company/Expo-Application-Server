package team.startup.application

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "application.registration.enabled=false",
        "management.endpoints.web.exposure.include=health,prometheus,info,env",
        "management.endpoint.health.show-components=always",
        "management.health.redis.enabled=false",
        "management.endpoint.health.group.live.include=ping",
        "management.endpoint.health.group.live.additional-path=server:/livez",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class SecurityConfigTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var mappings: RequestMappingHandlerMapping

    @Test
    fun `disabled docs have no handlers or anonymous permissions`() {
        listOf(
            "/application/v3/api-docs",
            "/application/v3/api-docs/swagger-config",
            "/application/swagger-ui.html",
            "/application/swagger-ui/index.html",
        ).forEach { path ->
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
            val expectedStatus = if (path == "/application/swagger-ui.html") 405 else 404
            mockMvc.perform(get(path).with(user("user"))).andExpect(status().`is`(expectedStatus))
        }
        assertFalse(
            mappings.handlerMethods.keys.any {
                it.patternValues.any { path ->
                    path.contains("api-docs") || path.contains("swagger")
                }
            },
        )
    }

    @Test
    fun `program public entry stays disabled by default`() {
        mockMvc.perform(get("/application/program-session-csrf")).andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                post("/application/expos/0199c000-0000-7000-8000-000000000001/program-sessions/STANDARD/1/applications")
                    .with(csrf())
                    .with(user("admin")),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `관리자 신청 현황 SSE는 기본값에서 인증 전에 404다`() {
        // 켜져 있으면 Authorization 없는 요청은 401이다(AdminDataChangeHttpTests). 운영 판별 근거(docs/admin-data-change-events.md).
        val path = "/application/admin/expos/0199c000-0000-7000-8000-000000000001/events"
        mockMvc.perform(get(path)).andExpect(status().isNotFound)
        mockMvc.perform(get(path).header("Authorization", "Bearer admin")).andExpect(status().isNotFound)
    }

    @Test
    fun `health와 prometheus는 인증 없이 GET 할 수 있다`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
        mockMvc.perform(get("/actuator/health/db")).andExpect(status().isOk)
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk)
        mockMvc.perform(head("/actuator/health")).andExpect(status().isOk)
        mockMvc.perform(get("/livez")).andExpect(status().isOk)
    }

    @Test
    fun `허용 경로 외의 요청은 인증이 필요하다`() {
        listOf("/actuator", "/actuator/info", "/actuator/env", "/actuator/healthz", "/")
            .forEach { mockMvc.perform(get(it)).andExpect(status().isUnauthorized) }
        mockMvc.perform(post("/actuator/prometheus").with(csrf())).andExpect(status().isUnauthorized)
        mockMvc.perform(head("/actuator/prometheus")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/application/v3/api-docs")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/application/swagger-ui.html")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `인증된 사용자는 나머지 actuator에 접근할 수 있다`() {
        mockMvc.perform(get("/actuator/info").with(user("user"))).andExpect(status().isOk)
    }

    @Test
    fun `비활성 등록 네 경로는 참가자 쿠키와 인증 주장으로 공개되지 않는다`() {
        registrationPaths().forEach { path ->
            mockMvc
                .perform(
                    post(path)
                        .with(csrf())
                        .cookie(Cookie("PARTICIPANT_SESSION", "unverified-session"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody),
                ).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `비활성 등록 네 경로는 일반 인증 사용자에게도 저장을 허용하지 않는다`() {
        registrationPaths().forEach { path ->
            mockMvc
                .perform(
                    post(path)
                        .with(csrf())
                        .with(user("representative"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody),
                ).andExpect(status().isServiceUnavailable)
        }
    }

    private fun registrationPaths() =
        listOf(
            "/application/$EXPO_ID",
            "/application/pre-standard/$EXPO_ID",
            "/application/field/$EXPO_ID",
            "/application/field/standard/$EXPO_ID",
        )

    @Test
    fun `잔여석 GET은 공개지만 원장 조회 구현이 준비되지 않으면 503을 반환한다`() {
        val path = "/application/expos/0199c000-0000-7000-8000-000000000001/preregister-sessions/7/capacity"
        mockMvc.perform(get(path)).andExpect(status().isServiceUnavailable)
        mockMvc.perform(head(path)).andExpect(status().isUnauthorized)
        mockMvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized)
        mockMvc.perform(get(path.substringBeforeLast('/'))).andExpect(status().isUnauthorized)
    }

    companion object {
        private const val EXPO_ID = "0199c000-0000-7000-8000-000000000001"
        private val registrationBody =
            """{"name":"대표자","phoneNumber":"01012345678","informationJson":"{}","personalInformationStatus":true,"trainingId":"training-1","sessionId":7,"verified":true}"""

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
