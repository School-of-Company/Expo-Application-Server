package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryQuery
import team.startup.application.domain.application.service.CheckTrainingProgramApplicationService
import team.startup.application.domain.application.service.GetAdminExpoHistoryService
import team.startup.application.domain.application.service.GetReportApplicationSnapshotService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLTransientConnectionException
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "application.internal-token=test-token",
        "spring.datasource.hikari.maximum-pool-size=1",
        "APPLICATION_DB_CONNECTION_TIMEOUT_MS=500",
    ],
)
@Testcontainers
class DatabaseConnectionHttpTests {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var dataSource: DataSource

    @MockitoBean private lateinit var training: CheckTrainingProgramApplicationService

    @MockitoBean private lateinit var history: GetAdminExpoHistoryService

    @MockitoBean private lateinit var snapshots: GetReportApplicationSnapshotService

    @Test
    fun `pool exhaustion returns 503 and requests recover after release`() {
        val path = "/internal/program-applications/standard/7/participants/42"
        dataSource.connection.use {
            val response = request(path)
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains("Database connection unavailable; retry the request"))
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        }
        assertEquals(200, request(path).statusCode())
    }

    @Test
    fun `JDBC and raw connection acquisition exceptions return sanitized 503`() {
        listOf(
            CannotGetJdbcConnectionException("private JDBC details"),
            SQLTransientConnectionException("private JDBC details"),
        ).forEach { failure ->
            doAnswer { throw failure }.`when`(training).execute(7, 42)
            val response = request("/internal/program-applications/training/7/trainees/42")
            assertEquals(503, response.statusCode())
            assertFalse(response.body().contains("private JDBC details"))
        }
    }

    @Test
    fun `controller specific database advices keep their response`() {
        val expoId = UUID.randomUUID()
        val failure = CannotGetJdbcConnectionException("private JDBC details")
        `when`(history.execute(expoId, null, AdminExpoHistoryQuery())).thenThrow(failure)
        `when`(snapshots.execute(expoId, "test")).thenThrow(failure)

        listOf(
            "/application/admin/expos/$expoId/recruitment-history",
            "/internal/expos/$expoId/program-session-applications/report-targets?cursor=test",
        ).forEach { path ->
            val response = request(path)
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains("Application source unavailable; retry the request"))
        }
    }

    @Test
    fun `unrelated failures remain server errors`() {
        `when`(training.execute(7, 42)).thenThrow(IllegalStateException("unrelated failure"))
        assertEquals(500, request("/internal/program-applications/training/7/trainees/42").statusCode())
    }

    private fun request(path: String): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            client.send(
                HttpRequest
                    .newBuilder(URI("http://localhost:$port$path"))
                    .header("X-Internal-Token", "test-token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
