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
                // V17 keeps waiting parties as reservations with a queue position; withdrawal reuses terminal REJECTED.
                val reservation =
                    "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants, state, " +
                        "queue_seq, withdrawn_at) VALUES ('00000000-0000-0000-0000-000000000034', ?, 1, '{}', '{}', 1, ?, ?, ?)"
                connection.prepareStatement(reservation).use { insert ->
                    fun attempt(
                        key: String,
                        state: String,
                        queue: Long?,
                        withdrawn: Boolean,
                    ) {
                        insert.setString(1, key)
                        insert.setString(2, state)
                        insert.setObject(3, queue)
                        insert.setObject(4, if (withdrawn) java.sql.Timestamp(0) else null)
                        insert.executeUpdate()
                    }
                    attempt("waiting", "WAITING", 1, false)
                    attempt("withdrawn", "REJECTED", 2, true)
                    assertThrows(SQLException::class.java) { attempt("unqueued", "WAITING", null, false) }
                    assertThrows(SQLException::class.java) { attempt("withdrawn-reserved", "RESERVED", 3, true) }
                }
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
