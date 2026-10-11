package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.repository.OwnerCancellationDeliveryRepository
import java.util.UUID

@Testcontainers
class OwnerCancellationDeliveryRepositoryTests {
    private lateinit var repository: OwnerCancellationDeliveryRepository
    private lateinit var transactions: TransactionTemplate
    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun start() {
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
        jdbc.execute("DROP TABLE IF EXISTS tb_owner_cancellation_delivery")
        jdbc.execute(
            ClassPathResource("db/migration/V14__owner_cancellation_delivery.sql").inputStream.bufferedReader().use { it.readText() },
        )
        repository = OwnerCancellationDeliveryRepository(jdbc, source)
        transactions = TransactionTemplate(DataSourceTransactionManager(source))
    }

    @Test
    fun `persisted operation identity and intended action survive repository recreation`() {
        val operation = UUID.randomUUID()
        repository.insert(COMMAND, 2, operation)
        val restarted = OwnerCancellationDeliveryRepository(JdbcTemplate(source), source)
        val pending = requireNotNull(restarted.find(COMMAND))
        assertEquals(operation, pending.operationId)
        assertEquals(2L, pending.participantId)
        assertEquals("ACQUIRING", pending.action)
        transactions.executeWithoutResult { restarted.commit(operation, 123) }
        assertEquals("COMMIT", requireNotNull(repository.find(COMMAND)).action)
        assertEquals(123L, requireNotNull(repository.find(COMMAND)).cancellationRevision)
        assertEquals(operation, repository.pending().single().operationId)
        assertEquals(
            "",
            repository
                .pending()
                .single()
                .command.sessionId,
        )
        repository.delete(operation)
        assertEquals(null, restarted.find(COMMAND))
    }

    @Test
    fun `local transaction rollback preserves uncertain acquisition for abort recovery`() {
        val operation = UUID.randomUUID()
        repository.insert(COMMAND, 2, operation)
        assertThrows(IllegalStateException::class.java) {
            transactions.executeWithoutResult {
                repository.commit(operation, 123)
                throw IllegalStateException("local cancellation failed")
            }
        }
        assertEquals("ACQUIRING", requireNotNull(repository.find(COMMAND)).action)
        assertEquals(operation, requireNotNull(repository.find(COMMAND)).operationId)
        repository.abort(operation)
        assertEquals("ABORT", requireNotNull(repository.operation(operation)).action)
        assertEquals("ABORT", requireNotNull(repository.find(COMMAND)).action)
    }

    @Test
    fun `same cancellation is serialized while unrelated application proceeds and close releases lock`() {
        repository.lock(COMMAND).use {
            val conflict = assertThrows(ResponseStatusException::class.java) { repository.lock(COMMAND) }
            assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
            repository.lock(COMMAND.copy(applicationId = "3")).use { }
        }
        repository.lock(COMMAND).use { }
    }

    @Test
    fun `completed recovery cannot delete another operation`() {
        val original = UUID.randomUUID()
        repository.insert(COMMAND, 2, original)
        repository.delete(original)
        val next = UUID.randomUUID()
        assertNotEquals(original, next)
        repository.insert(COMMAND, 2, next)
        repository.delete(original)
        assertEquals(next, requireNotNull(repository.find(COMMAND)).operationId)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        private val COMMAND =
            CancelOwnerApplicationCommand(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045"),
                "opaque-session",
                OwnerApplicationType.PREREGISTER,
                "2",
            )
    }
}
