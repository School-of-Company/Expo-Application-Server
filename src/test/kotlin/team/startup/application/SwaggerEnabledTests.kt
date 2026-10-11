package team.startup.application

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "SWAGGER_ENABLED=true",
        "application.program-sessions.enabled=true",
        "application.registration.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
open class SwaggerEnabledTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun `public document excludes internal paths and their private schemas`() {
        mockMvc
            .perform(get("/application/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/internal/standard-program-applications']").doesNotExist())
            .andExpect(jsonPath("$.info.title").value("Expo Application Server API"))
            .andExpect(jsonPath("$.info.version").value("0.1.0"))
            .andExpect(jsonPath("$.servers[0].url").value("/"))
            .andExpect(jsonPath("$.paths['/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity'].get").exists())
            .andExpect(
                jsonPath("$.paths['/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity'].get.responses['503']").exists(),
            ).andExpect(jsonPath("$.components.schemas.RegistrationRequest.properties.name.example").value("홍길동"))
            .andExpect(jsonPath("$.components.schemas.PreregisterSessionCapacityResponse.properties.remaining.description").exists())
            .andExpect(content().string(not(containsString("\"/internal/"))))
            .andExpect(jsonPath("$.components.securitySchemes.internalToken").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.StandardApplicationRequest").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.PreregisterReservationStatus").doesNotExist())
            .andExpect(jsonPath("$.paths['/application/pre-standard/{expoId}'].post.responses['502']").exists())
            .andExpect(
                jsonPath(
                    "$.paths['/application/expos/{expoId}/my-applications/{type}/{applicationId}/cancellation'].post.responses['404']",
                ).exists(),
            ).andExpect(
                jsonPath(
                    "$.paths['/application/expos/{expoId}/program-sessions/{programType}/{programId}/applications'].post.responses['403']",
                ).exists(),
            )

        val document =
            mockMvc
                .perform(get("/application/v3/api-docs"))
                .andReturn()
                .response.contentAsString
        val schemas = mapper.readTree(document).path("components").path("schemas")
        Regex("#/components/schemas/([^\"]+)\"").findAll(document).forEach {
            assertTrue(schemas.has(it.groupValues[1]), "Missing referenced schema: ${it.groupValues[1]}")
        }
        assertFalse(document.contains("test-token"))
    }

    @Test
    fun `server stays relative when gateway forwards a backend Host`() {
        mockMvc
            .perform(get("/application/v3/api-docs").header("Host", "application-backend.example:8080"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.servers[0].url").value("/"))
    }

    @Test
    fun `swagger UI and config are reachable only by GET`() {
        mockMvc.perform(get("/application/swagger-ui.html")).andExpect(status().is3xxRedirection)
        mockMvc.perform(get("/application/swagger-ui/index.html")).andExpect(status().isOk)
        mockMvc
            .perform(get("/application/v3/api-docs/swagger-config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value("/application/v3/api-docs"))
        mockMvc.perform(post("/application/v3/api-docs")).andExpect(status().isForbidden)
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized)
        listOf(
            "/application/v3/api-docs",
            "/application/v3/api-docs/swagger-config",
            "/application/swagger-ui.html",
            "/application/swagger-ui/index.html",
        ).forEach { path ->
            listOf(HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH).forEach { method ->
                mockMvc.perform(request(method, path).with(csrf())).andExpect(status().isUnauthorized)
                mockMvc.perform(request(method, path).with(csrf()).with(user("user"))).andExpect(status().isForbidden)
            }
        }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
