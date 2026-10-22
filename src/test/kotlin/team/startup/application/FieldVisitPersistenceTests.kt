package team.startup.application

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class FieldVisitPersistenceTests {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var visits: FieldVisitRegistrationRepository
    private lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun setup() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute("TRUNCATE tb_field_visit_registration CASCADE")
        visits = FieldVisitRegistrationRepository(jdbc)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `동시 오늘 등록은 한 행과 한 이벤트이고 다른날은 별도 행이다`() {
        val expoId = UUID.randomUUID()
        val day = LocalDate.of(2026, 10, 22)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..2).map {
                    executor.submit<UUID> {
                        start.await(10, TimeUnit.SECONDS)
                        requireNotNull(transactions.execute { visits.activate(expoId, 42, day, UUID.randomUUID()).id })
                    }
                }
            start.countDown()
            assertEquals(1, futures.map { it.get(10, TimeUnit.SECONDS) }.distinct().size)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_field_visit_event", Int::class.java))
            transactions.execute { visits.activate(expoId, 42, day.plusDays(1), UUID.randomUUID()) }
            assertEquals(2, visits.findByParticipants(expoId, listOf(42)).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `취소 전달중 재활성화 금지와 세대 CAS는 과거 재시도로부터 새 등록을 보호한다`() {
        val original =
            requireNotNull(transactions.execute { visits.activate(UUID.randomUUID(), 42, LocalDate.of(2026, 10, 22), UUID.randomUUID()) })
        val operation = UUID.randomUUID()
        assertFalse(visits.beginCancellation(original.id, 1, operation))
        visits.delivered(original.id, 1)
        transactions.execute {
            assertTrue(visits.beginCancellation(original.id, 1, operation))
            assertTrue(visits.cancel(original.id, 1, 42, operation))
            assertFalse(visits.cancel(original.id, 1, 42, operation))
        }
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            transactions.execute { visits.activate(original.expoId, 42, original.visitDate, UUID.randomUUID()) }
        }
        assertFalse(visits.clearCancellation(original.id, 1, UUID.randomUUID()))
        assertTrue(visits.clearCancellation(original.id, 1, operation))
        val next = requireNotNull(transactions.execute { visits.activate(original.expoId, 42, original.visitDate, UUID.randomUUID()) })
        assertEquals(original.id, next.id)
        assertEquals(2, next.generation)
        assertFalse(visits.beginCancellation(original.id, 1, operation))
        assertFalse(visits.cancel(original.id, 1, 42, operation))
        assertEquals("ACTIVE", visits.findById(next.id)?.status)
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM tb_field_visit_event", Int::class.java))
    }

    @Test
    fun `사전등록 방문일은 offset 순간과 KST local 양식을 모두 KST 날짜로 읽는다`() {
        val expoId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_preregister_session_state(expo_id,session_id,definition) VALUES (?,1,?)",
            expoId,
            """{"startedAt":"2026-10-22T18:00:00"}""",
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application(expo_id,session_id,representative_id,participant_id,status) " +
                "VALUES (?,1,42,42,'CONFIRMED')",
            expoId,
        )
        assertTrue(visits.preregistered(expoId, 42, LocalDate.of(2026, 10, 22)))
        assertFalse(visits.preregistered(expoId, 42, LocalDate.of(2026, 10, 23)))
        jdbc.update(
            "UPDATE tb_preregister_session_state SET definition=? WHERE expo_id=?",
            """{"startedAt":"2026-10-21T15:00:00Z"}""",
            expoId,
        )
        assertTrue(visits.preregistered(expoId, 42, LocalDate.of(2026, 10, 22)))
        assertFalse(visits.preregistered(expoId, 42, LocalDate.of(2026, 10, 21)))
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
