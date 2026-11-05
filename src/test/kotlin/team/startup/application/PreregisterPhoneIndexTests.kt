package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.repository.reservationPhoneSql
import java.sql.DriverManager
import java.util.UUID

@Testcontainers
class PreregisterPhoneIndexTests {
    @Test
    fun `normalized reservation phone lookups use the V8 expression index with and without an alias`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            val expoId = UUID.randomUUID()
            jdbc.execute("CREATE TABLE tb_preregister_reservation (expo_id UUID NOT NULL, user_request TEXT NOT NULL)")
            jdbc.update(
                "INSERT INTO tb_preregister_reservation SELECT ?, " +
                    "jsonb_build_object('phoneNumber', '010-' || lpad(n::text, 8, '0'))::text FROM generate_series(1, 10000) n",
                expoId,
            )
            jdbc.execute(requireNotNull(javaClass.getResource("/db/migration/V8__preregister_phone_index.sql")).readText())
            jdbc.execute("ANALYZE tb_preregister_reservation")

            for (alias in listOf("", "r.")) {
                val query =
                    "SELECT count(*) FROM tb_preregister_reservation ${alias.removeSuffix(".")} " +
                        "WHERE expo_id = ? AND ${reservationPhoneSql(alias)} = ?"
                assertEquals(1L, jdbc.queryForObject(query, Long::class.java, expoId, "01000000042"))
                val plan =
                    jdbc.queryForList("EXPLAIN $query", String::class.java, expoId, "01000000042").joinToString("\n")
                assertTrue(plan.contains("ix_preregister_reservation_phone"), plan)
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
