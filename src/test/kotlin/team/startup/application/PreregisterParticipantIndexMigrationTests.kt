package team.startup.application

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

@Testcontainers
class PreregisterParticipantIndexMigrationTests {
    @Test
    fun `upgrade concurrently adds participant index used by waiting count`() {
        val config =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
        config.target("44").load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            jdbc.execute(
                "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) " +
                    "SELECT ('00000000-0000-0000-0000-' || lpad(e::text, 12, '0'))::uuid, s, '{}' " +
                    "FROM generate_series(1, 20) e CROSS JOIN generate_series(1, 2) s",
            )
            jdbc.execute(
                "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                    "SELECT ('00000000-0000-0000-0000-' || lpad(e::text, 12, '0'))::uuid, s, p, p, " +
                    "CASE WHEN p % 5 = 0 THEN 'CANCELLED' ELSE 'CONFIRMED' END " +
                    "FROM generate_series(1, 20) e CROSS JOIN generate_series(1, 5000) p CROSS JOIN generate_series(1, 2) s",
            )
            jdbc.execute(
                "INSERT INTO tb_program_session_application " +
                    "(id, expo_id, program_type, program_id, group_id, participant_id, status, " +
                    "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot) " +
                    "SELECT md5(p::text)::uuid, '00000000-0000-0000-0000-000000000001', 'STANDARD', 1, " +
                    "md5('group')::uuid, p, 'WAITING', '{}', md5('form')::uuid, 1, '[]', '{}' FROM generate_series(1, 500) p",
            )
            jdbc.execute("VACUUM ANALYZE tb_preregister_application")
            jdbc.execute("VACUUM ANALYZE tb_program_session_application")
            val waitingCount =
                "SELECT count(*) FROM tb_program_session_application " +
                    "WHERE expo_id = '00000000-0000-0000-0000-000000000001' AND program_type = 'STANDARD' " +
                    "AND program_id = 1 AND status = 'WAITING' AND (CAST(NULL AS text) IS NULL OR channel = NULL) " +
                    "AND (status <> 'WAITING' OR EXISTS (" +
                    "SELECT 1 FROM tb_preregister_application p WHERE p.expo_id = tb_program_session_application.expo_id " +
                    "AND p.participant_id = tb_program_session_application.participant_id AND p.status = 'CONFIRMED'))"
            val index = "ix_preregister_application_participant"

            fun explain(): String = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) $waitingCount", String::class.java).joinToString("\n")

            assertFalse(explain().contains(index))
            assertEquals(400L, jdbc.queryForObject(waitingCount, Long::class.java))

            config.target("latest").load().migrate()

            assertEquals(
                "CREATE INDEX $index ON public.tb_preregister_application USING btree (expo_id, participant_id)",
                jdbc.queryForObject("SELECT pg_get_indexdef(?::regclass)", String::class.java, index),
            )
            assertEquals(
                true,
                jdbc.queryForObject("SELECT indisvalid FROM pg_index WHERE indexrelid = ?::regclass", Boolean::class.java, index),
            )
            val plan = explain()
            assertTrue(plan.contains(index), plan)
            assertEquals(400L, jdbc.queryForObject(waitingCount, Long::class.java))
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
