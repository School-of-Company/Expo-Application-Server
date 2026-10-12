package team.startup.application

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
        "SWAGGER_INCLUDE_INTERNAL=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class SwaggerIncludeInternalTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun `internal document requires X Internal Token without revealing its value`() {
        mockMvc
            .perform(get("/application/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/internal/standard-program-applications'].post.security[0].internalToken").exists())
            .andExpect(jsonPath("$.components.securitySchemes.internalToken.name").value("X-Internal-Token"))
            .andExpect(jsonPath("$.components.securitySchemes.internalToken.type").value("apiKey"))
            .andExpect(jsonPath("$.components.securitySchemes.internalToken.in").value("header"))
            .andExpect(jsonPath("$.paths['/internal/standard-program-applications'].post.security[0].bearerAuth").doesNotExist())
            .andExpect(
                jsonPath(
                    "$.paths['/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity'].get.security[0].bearerAuth",
                ).exists(),
            ).andExpect(jsonPath("$.components.schemas.StandardApplicationRequest").exists())
            .andExpect(jsonPath("$.components.schemas.PreregisterReservationStatus").exists())
            .andExpect(content().string(not(containsString("test-token"))))
        val document =
            mapper.readTree(
                mockMvc
                    .perform(get("/application/v3/api-docs"))
                    .andReturn()
                    .response.contentAsString,
            )
        document.path("paths").properties().filter { it.key.startsWith("/internal/") }.forEach { path ->
            path.value.properties().filter { it.key in setOf("get", "post", "put", "delete", "patch") }.forEach { operation ->
                assertEquals(
                    "[]",
                    operation.value
                        .path("security")
                        .path(0)
                        .path("internalToken")
                        .toString(),
                    path.key,
                )
                assertEquals(true, operation.value.path("responses").has("401"), path.key)
            }
        }
        assertEquals(
            true,
            document
                .path(
                    "paths",
                ).path("/internal/expos/{expoId}/program-session-applications/report-targets")
                .path("post")
                .path("responses")
                .has("422"),
        )
        assertEquals(
            true,
            document
                .path(
                    "paths",
                ).path("/internal/expos/{expoId}/program-session-applications/report-targets")
                .path("get")
                .path("responses")
                .has("410"),
        )
        mockMvc.perform(get("/internal/standard-program-applications")).andExpect(status().isUnauthorized)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
