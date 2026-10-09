package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ApplyPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey
import team.startup.application.domain.application.service.ApplyPreregisterSessionService
import team.startup.application.domain.application.service.CancelPreregisterSessionService
import team.startup.application.domain.application.service.PromotePreregisterSessionService
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import team.startup.application.domain.application.service.SetPreregisterAutoPromotionCommand
import team.startup.application.domain.application.service.SetPreregisterAutoPromotionService
import team.startup.application.domain.application.service.impl.PreregisterSessionGateway
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Testcontainers
class SessionAttendanceLedgerTests {
    @Autowired private lateinit var apply: ApplyPreregisterSessionService

    @Autowired private lateinit var cancel: CancelPreregisterSessionService

    @Autowired private lateinit var promote: PromotePreregisterSessionService

    @Autowired private lateinit var autoPromotion: SetPreregisterAutoPromotionService

    @Autowired private lateinit var purge: PurgePreregisterSessionsService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var gateway: PreregisterSessionGateway

    private val expo = UUID.fromString("00000000-0000-0000-0000-000000000036")

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_session_attendance_outbox, tb_preregister_transition, tb_preregister_request, " +
                "tb_preregister_application, tb_preregister_session_change, tb_preregister_session_state, " +
                "tb_preregister_deleted_expo RESTART IDENTITY CASCADE",
        )
        val definition =
            PreregisterSessionDefinition(
                1,
                expo,
                "Morning",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                "Place",
                2,
                3,
                false,
                1,
            )
        `when`(gateway.find(expo, 1)).thenReturn(definition)
        `when`(gateway.find(expo, 2)).thenReturn(definition.copy(id = 2))
    }

    @Test
    fun `실제 일행 신청은 확정자만 기록하며 요청 재시도와 겹친 참가자는 중복 기록하지 않는다`() {
        val command = command(1, 2, 3, 4, 5)
        val receipt = apply.execute(command)
        assertEquals(listOf("PUT:1:1", "PUT:2:1"), pending())
        assertEquals(receipt, apply.execute(command))
        apply.execute(command(1, 3))
        assertEquals(listOf("PUT:1:1", "PUT:2:1"), pending())
    }

    @Test
    fun `확정자 취소와 FIFO 승급은 DELETE와 PUT을 함께 저장하고 대기 취소와 반복 취소는 보내지 않는다`() {
        val receipt = apply.execute(command(1, 2, 3, 4, 5))
        val cancellation = CancelPreregisterSessionCommand(expo, 1, 1, listOf(receipt[0].id, receipt[3].id))
        cancel.execute(cancellation)
        assertEquals(listOf("PUT:1:1", "PUT:2:1", "DELETE:1", "PUT:3:1"), pending())
        cancel.execute(cancellation)
        assertEquals(listOf("PUT:1:1", "PUT:2:1", "DELETE:1", "PUT:3:1"), pending())
    }

    @Test
    fun `자동승급 OFF에서 실제 수동승급도 같은 확정 통지를 저장한다`() {
        val receipt = apply.execute(command(1, 2, 3))
        autoPromotion.execute(SetPreregisterAutoPromotionCommand(PreregisterSessionKey(expo, 1), false))
        cancel.execute(CancelPreregisterSessionCommand(expo, 1, null, listOf(receipt[0].id)))
        assertEquals(listOf("PUT:1:1", "PUT:2:1", "DELETE:1"), pending())
        assertEquals(listOf(3L), promote.execute(PreregisterSessionKey(expo, 1)).map { it.participantId })
        assertEquals(listOf("PUT:1:1", "PUT:2:1", "DELETE:1", "PUT:3:1"), pending())
        promote.execute(PreregisterSessionKey(expo, 1))
        assertEquals(4, pending().size)
    }

    @Test
    fun `취소 후 다른 회차에 재신청하면 실제 원장 순서대로 DELETE 다음 PUT을 저장한다`() {
        val receipt = apply.execute(command(1)).single()
        val cancellation = CancelPreregisterSessionCommand(expo, 1, 1, listOf(receipt.id))
        cancel.execute(cancellation)
        apply.execute(command(1).copy(sessionId = 2))
        cancel.execute(cancellation)
        assertEquals(listOf("PUT:1:1", "DELETE:1", "PUT:1:2"), pending())
    }

    @Test
    fun `실제 신청 롤백은 원장과 멱등 결과와 통지를 모두 제거한다`() {
        jdbc.execute("ALTER TABLE tb_preregister_request ADD CONSTRAINT test_reject_receipt CHECK (request_id <> 'rollback-request')")
        try {
            assertThrows(DataIntegrityViolationException::class.java) {
                apply.execute(command(1, 2, 3).copy(requestId = "rollback-request"))
            }
        } finally {
            jdbc.execute("ALTER TABLE tb_preregister_request DROP CONSTRAINT test_reject_receipt")
        }
        assertEquals(emptyList<String>(), pending())
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_preregister_application", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_preregister_request", Int::class.java))
    }

    @Test
    fun `박람회 원장을 정리해도 미발송 취소 기록을 삭제하지 않는다`() {
        val receipt = apply.execute(command(1)).single()
        cancel.execute(CancelPreregisterSessionCommand(expo, 1, null, listOf(receipt.id)))
        purge.execute(expo)
        assertEquals(listOf("PUT:1:1", "DELETE:1"), pending())
    }

    private fun command(vararg participants: Long) =
        ApplyPreregisterSessionCommand(expo, 1, UUID.randomUUID().toString(), 1, participants.toList())

    private fun pending(): List<String> =
        jdbc.query(
            "SELECT participant_id, session_id FROM tb_session_attendance_outbox ORDER BY id",
            { rs, _ ->
                val session = rs.getObject("session_id", Long::class.javaObjectType)
                if (session == null) "DELETE:${rs.getLong("participant_id")}" else "PUT:${rs.getLong("participant_id")}:$session"
            },
        )

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
