package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Testcontainers
class AttendanceAuditSqlTests {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `출석 집계는 빈 테이블과 모든 기록 유형을 읽기 전용으로 확인한다`() {
        jdbc.execute("TRUNCATE TABLE tb_standard_program_application, tb_training_program_application RESTART IDENTITY")
        assertEquals(listOf(List(8) { 0L }, List(8) { 0L }), audit())

        jdbc.update(
            """
            INSERT INTO tb_standard_program_application
            (participant_id, standard_program_id, status, entry_time, leave_time, attendance_date) VALUES
            (1, 7, false, null, null, null),
            (2, 7, true, '09:00:30', null, '2026-10-07'),
            (3, 7, false, '09:00', null, null),
            (4, 7, false, null, '10:00', null),
            (5, 7, false, null, null, '2026-10-07'),
            (6, 7, false, null, '10:00', null),
            (7, 7, false, '09:00', null, '2026-10-07'),
            (8, 7, false, null, null, '2026-10-07'),
            (9, 7, false, null, null, '2026-10-07'),
            (10, 7, false, null, null, '2026-10-07'),
            (11, 7, false, null, null, '2026-10-07'),
            (12, 7, false, null, null, '2026-10-07'),
            (13, 7, true, null, null, null),
            (14, 7, false, '09:00', '10:00', '2026-10-07')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO tb_training_program_application
            (trainee_id, training_program_id, status, entry_time, leave_time, attendance_date)
            SELECT participant_id + copy * 100, standard_program_id, status, entry_time, leave_time, attendance_date
            FROM tb_standard_program_application CROSS JOIN generate_series(0, 1) AS copy
            """.trimIndent(),
        )
        val before = jdbc.queryForList("SELECT * FROM tb_standard_program_application ORDER BY id")
        val trainingBefore = jdbc.queryForList("SELECT * FROM tb_training_program_application ORDER BY id")
        assertEquals(
            listOf(
                listOf(14L, 2L, 4L, 3L, 9L, 13L, 10L, 11L),
                listOf(28L, 4L, 8L, 6L, 18L, 26L, 20L, 22L),
            ),
            audit(),
        )
        assertEquals(before, jdbc.queryForList("SELECT * FROM tb_standard_program_application ORDER BY id"))
        assertEquals(trainingBefore, jdbc.queryForList("SELECT * FROM tb_training_program_application ORDER BY id"))
    }

    private fun audit(): List<List<Long>> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val rows = mutableListOf<List<Long>>()
                Files.readString(Path.of("docs/attendance-audit.sql")).split(';').filter { it.isNotBlank() }.forEach { sql ->
                    if (statement.execute(sql)) {
                        statement.resultSet.use { result ->
                            while (result.next()) rows.add((2..9).map { result.getLong(it) })
                        }
                        connection.createStatement().use { check ->
                            check.executeQuery("SHOW transaction_read_only").use { result ->
                                result.next()
                                assertEquals("on", result.getString(1))
                            }
                        }
                    }
                }
                rows
            }
        }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
