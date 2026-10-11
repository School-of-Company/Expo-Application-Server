package team.startup.application

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException

@Testcontainers
class PreregisterMigrationTests {
    @Test
    fun `upgrade preserves history refuses live waiting applicants and prohibits new waiting state`() {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        config.target("6").load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute(
                    "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) " +
                        "VALUES ('00000000-0000-0000-0000-000000000034', 1, '{}')",
                )
                sql.execute(
                    "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                        "VALUES ('00000000-0000-0000-0000-000000000034', 1, 42, 42, 'WAITING')",
                )
                sql.execute(
                    "INSERT INTO tb_preregister_transition (application_id, expo_id, session_id, participant_id, status) " +
                        "VALUES (1, '00000000-0000-0000-0000-000000000034', 1, 42, 'WAITING')",
                )
                val upgrade = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load()
                val failure = assertThrows(FlywayException::class.java) { upgrade.migrate() }
                assertTrue(failure.message.orEmpty().contains("Resolve legacy WAITING"))
                sql.execute("UPDATE tb_preregister_application SET status = 'CANCELLED'")
                upgrade.migrate()
                sql.executeQuery("SELECT status FROM tb_preregister_transition").use {
                    assertTrue(it.next())
                    assertEquals("WAITING", it.getString(1))
                }
                assertThrows(SQLException::class.java) { sql.execute("UPDATE tb_preregister_application SET status = 'WAITING'") }
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
