package team.startup.application

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
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
    }

    @Test
    fun `인증된 사용자는 나머지 actuator에 접근할 수 있다`() {
        mockMvc.perform(get("/actuator/info").with(user("user"))).andExpect(status().isOk)
    }

    @Test
    fun `잔여석 GET은 공개지만 원장 조회 구현이 준비되지 않으면 503을 반환한다`() {
        val path = "/application/expos/0199c000-0000-7000-8000-000000000001/preregister-sessions/7/capacity"
        mockMvc.perform(get(path)).andExpect(status().isServiceUnavailable)
        mockMvc.perform(head(path)).andExpect(status().isUnauthorized)
        mockMvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized)
        mockMvc.perform(get(path.substringBeforeLast('/'))).andExpect(status().isUnauthorized)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
