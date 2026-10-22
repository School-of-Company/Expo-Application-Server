package team.startup.application

import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ApplicationNotificationRepository
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.service.impl.ApplicationNoticeSource
import team.startup.application.domain.application.service.impl.ApplicationNotificationPublisher
import team.startup.application.domain.application.service.impl.ApplicationNotificationResolver
import team.startup.application.domain.application.service.impl.ApplicationNotifications
import team.startup.application.domain.application.service.impl.NoticeContact
import team.startup.application.domain.application.service.impl.ProgramPromotionGateway
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Future

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Testcontainers
class ApplicationNotificationTests {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var repository: ApplicationNotificationRepository

    @Autowired private lateinit var programs: ProgramWaitlistRepository

    @Autowired private lateinit var preregister: PreregisterSessionRepository

    @Autowired private lateinit var reservations: PreregisterReservationRepository

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var managed: ApplicationNotifications

    private val notifications get() = ApplicationNotifications(repository, true, emptyList())

    private val contacts = mutableMapOf<Long, NoticeContact>()
    private val source =
        object : ApplicationNoticeSource {
            override fun contact(
                expoId: UUID,
                participantId: Long,
            ): NoticeContact = contacts.getValue(participantId)

            override fun programNotice(
                expoId: UUID,
                type: ProgramSessionType,
                programId: Long,
            ) = ProgramPromotionGateway.ProgramNotice("AI 코딩 체험", "2026-10-30T10:00:00+09:00", "2026-10-30T11:00:00+09:00", "A관 101호")
        }

    // 첫 발행만 실패한다.
    private class FlakyProducer : MockProducer<String, String>(true, null, StringSerializer(), StringSerializer()) {
        var failures = 0

        override fun send(
            record: ProducerRecord<String, String>,
            callback: Callback?,
        ): Future<RecordMetadata> {
            if (failures > 0) {
                failures -= 1
                throw IllegalStateException("broker down")
            }
            return super.send(record, callback)
        }
    }

    private val producer = FlakyProducer()

    private fun publisher(expoIds: List<String> = emptyList()) =
        ApplicationNotificationPublisher(
            repository,
            ApplicationNotificationResolver(source, programs, preregister, reservations),
            jdbc,
            producer,
            mapper,
            "2020-01-01T00:00:00Z",
            "notification.sms.requested",
            expoIds,
        )

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_program_session_application, tb_application_notification, tb_preregister_application, tb_preregister_session_state CASCADE",
        )
        contacts.clear()
        contacts[2] = NoticeContact("01011112222", "홍길동")
        contacts[3] = NoticeContact("01011112222", "홍길순")
        producer.clear()
    }

    private fun application(
        participantId: Long = 2,
        status: String = "CONFIRMED",
        type: String = "STANDARD",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id,expo_id,program_type,program_id,group_id,participant_id,trainee_id,status," +
                "definition_snapshot,form_id,form_version,questions_snapshot,answers_snapshot) " +
                "VALUES (?, ?, ?, 3, ?, ?, 9, ?, '{}', ?, 1, '[]', '{}')",
            id,
            EXPO,
            type,
            UUID.randomUUID(),
            participantId,
            status,
            UUID.randomUUID(),
        )
        return id
    }

    private fun setStatus(
        id: UUID,
        status: String,
    ) = jdbc.update("UPDATE tb_program_session_application SET status = ? WHERE id = ?", status, id)

    private fun record(block: ApplicationNotifications.() -> Unit) {
        transactions.executeWithoutResult { notifications.block() }
    }

    private fun sent(): List<JsonNode> = producer.history().map { mapper.readTree(it.value()) }

    private fun count(where: String = "TRUE") =
        jdbc.queryForObject("SELECT count(*) FROM tb_application_notification WHERE $where", Long::class.java)!!

    @Test
    fun `confirmed application sends the representative number with the actual participant and program`() {
        val id = application()
        record { programAdmitted(EXPO, id, "CONFIRMED") }

        publisher().publishDue()

        val event = sent().single()
        assertEquals("PROGRAM_CONFIRMED", event["type"].asString())
        assertEquals("01011112222", event["phoneNumber"].asString())
        assertEquals(EXPO.toString(), event["expoId"].asString())
        assertEquals("STANDARD", event["senderType"].asString())
        assertEquals(1, event["version"].asInt())
        val item = event["items"].single()
        assertEquals("홍길동", item["participantName"].asString())
        assertEquals("AI 코딩 체험", item["title"].asString())
        assertEquals("2026-10-30T10:00:00+09:00", item["startedAt"].asString())
        assertEquals("A관 101호", item["location"].asString())
        assertNotNull(jdbc.queryForObject("SELECT delivered_at FROM tb_application_notification", java.sql.Timestamp::class.java))
    }

    @Test
    fun `training applications use the trainee sender and waiting applications the waitlist type`() {
        val id = application(status = "WAITING", type = "TRAINING")
        record { programAdmitted(EXPO, id, "WAITING") }

        publisher().publishDue()

        val event = sent().single()
        assertEquals("PROGRAM_WAITLISTED", event["type"].asString())
        assertEquals("TRAINEE", event["senderType"].asString())
    }

    @Test
    fun `retry and republish keep the same event id and never create a new notification`() {
        val id = application()
        record { programAdmitted(EXPO, id, "CONFIRMED") }
        producer.failures = 1

        publisher().publishDue()
        assertEquals(0, sent().size)
        assertEquals(1L, count("delivered_at IS NULL AND attempts = 1"))
        // 실패한 행은 백오프 뒤에만 다시 나간다.
        jdbc.update("UPDATE tb_application_notification SET next_attempt_at = clock_timestamp()")

        publisher().publishDue()
        val first = sent().single()["eventId"].asString()
        // 발행 뒤 완료 표시가 사라진 것(커밋 직후 중단)을 흉내 내 다시 발행해도 같은 eventId다.
        jdbc.update("UPDATE tb_application_notification SET delivered_at = NULL")
        publisher().publishDue()

        assertEquals(listOf(first, first), sent().map { it["eventId"].asString() })
        // 같은 상태 변경을 다시 기록해도 새 알림은 생기지 않는다.
        record { programAdmitted(EXPO, id, "CONFIRMED") }
        assertEquals(1L, count())
    }

    @Test
    fun `a confirmation delayed past a cancellation is skipped as stale`() {
        val id = application()
        record { programAdmitted(EXPO, id, "CONFIRMED") }
        setStatus(id, "CANCELLED")

        publisher().publishDue()

        assertEquals(0, sent().size)
        assertEquals("STATE_CHANGED", jdbc.queryForObject("SELECT skip_reason FROM tb_application_notification", String::class.java))
    }

    @Test
    fun `a waitlist notice is skipped once the application was promoted`() {
        val id = application(status = "WAITING")
        record { programAdmitted(EXPO, id, "WAITING") }
        setStatus(id, "CONFIRMED")

        publisher().publishDue()

        assertEquals(0, sent().size)
        assertEquals(1L, count("skip_reason = 'STATE_CHANGED'"))
    }

    @Test
    fun `cancellation names the actor and only owner or admin cancellations are recorded`() {
        val admin = application(participantId = 2)
        val owner = application(participantId = 3)
        val system = application(participantId = 4)
        val unknown = application(participantId = 5)
        listOf(admin, owner, system, unknown).forEach { setStatus(it, "CANCELLED") }

        record {
            programCancelled(EXPO, admin, ApplicationActorType.ADMIN)
            programCancelled(EXPO, owner, ApplicationActorType.USER)
            programCancelled(EXPO, system, ApplicationActorType.SYSTEM)
            programCancelled(EXPO, unknown, ApplicationActorType.UNKNOWN)
        }
        assertEquals(2L, count())

        publisher().publishDue()

        // 같은 대표자 번호라도 취소 주체가 다르면 따로 안내한다.
        assertEquals(setOf("ADMIN", "USER"), sent().map { it["cancelledBy"].asString() }.toSet())
        assertEquals(
            listOf("홍길동", "홍길순").sorted(),
            sent()
                .flatMap { e ->
                    e["items"].toList().map { it["participantName"].asString() }
                }.sorted(),
        )
    }

    @Test
    fun `partial cancellation of a party lists only who was cancelled in one message`() {
        val a = application(participantId = 2)
        val b = application(participantId = 3)
        setStatus(a, "CANCELLED")
        setStatus(b, "CANCELLED")

        record {
            programCancelled(EXPO, a, ApplicationActorType.USER)
            programCancelled(EXPO, b, ApplicationActorType.USER)
        }
        publisher().publishDue()

        val event = sent().single()
        assertEquals(setOf("홍길동", "홍길순"), event["items"].toList().map { it["participantName"].asString() }.toSet())
    }

    @Test
    fun `different representatives in one transaction get separate messages`() {
        contacts[3] = NoticeContact("01099998888", "김철수")
        val a = application(participantId = 2)
        val b = application(participantId = 3)
        record {
            programAdmitted(EXPO, a, "CONFIRMED")
            programAdmitted(EXPO, b, "CONFIRMED")
        }

        publisher().publishDue()

        assertEquals(setOf("01011112222", "01099998888"), sent().map { it["phoneNumber"].asString() }.toSet())
        assertEquals(2, sent().size)
    }

    @Test
    fun `a missing or invalid representative number is skipped and never replaced by another number`() {
        contacts[2] = NoticeContact(null, "홍길동")
        contacts[3] = NoticeContact("0212345678", "홍길순")
        val a = application(participantId = 2)
        val b = application(participantId = 3)
        record {
            programAdmitted(EXPO, a, "CONFIRMED")
            programAdmitted(EXPO, b, "CONFIRMED")
        }

        publisher().publishDue()

        assertEquals(0, sent().size)
        assertEquals(2L, count("skip_reason = 'NO_REPRESENTATIVE_PHONE'"))
    }

    @Test
    fun `lookup failures defer the whole batch and keep it retryable`() {
        val id = application(participantId = 99)
        record { programAdmitted(EXPO, id, "CONFIRMED") }

        assertThrows(NoSuchElementException::class.java) { publisher().publish(repository.due(Instant.EPOCH).single()) }

        assertEquals(1L, count("delivered_at IS NULL AND skipped_at IS NULL AND attempts = 1"))
    }

    @Test
    fun `offer expiry is recorded only for the expired transition and is sent for the cancelled application`() {
        val expired = application(status = "CANCELLED")
        record { programOfferExpired(EXPO, expired) }

        publisher().publishDue()

        val event = sent().single()
        assertEquals("OFFER_EXPIRED", event["type"].asString())
        assertEquals("홍길동", event["items"].single()["participantName"].asString())
    }

    private fun preregisterSession(): Long {
        preregister.saveDefinition(
            PreregisterSessionDefinition(
                7,
                EXPO,
                "10/30 오전 회차",
                Instant.parse("2026-10-30T01:00:00Z"),
                Instant.parse("2026-10-30T03:00:00Z"),
                "본관 대강당",
                50,
                false,
                1,
            ),
        )
        return 7
    }

    @Test
    fun `preregister cancellation says all only when nothing else remains for the representative`() {
        val session = preregisterSession()
        preregister.saveDefinition(preregister.definition(EXPO, session)!!.copy(id = session + 1))
        val first = preregister.insert(EXPO, session, 2, 2, "CONFIRMED")
        val second = preregister.insert(EXPO, session + 1, 2, 2, "CONFIRMED")
        preregister.status(first.id, "CANCELLED", ApplicationActorType.USER, 2)
        record { preregisterCancelled(EXPO, first.id, session, 2, ApplicationActorType.USER) }

        publisher().publishDue()

        val partial = sent().single()
        assertEquals("PREREGISTER_CANCELLED", partial["type"].asString())
        assertEquals("PARTIAL", partial["scope"].asString())
        assertEquals("10/30 오전 회차", partial["items"].single()["title"].asString())
        assertEquals("2026-10-30T10:00:00+09:00", partial["items"].single()["startedAt"].asString())

        preregister.status(second.id, "CANCELLED", ApplicationActorType.ADMIN, null, 1)
        record { preregisterCancelled(EXPO, second.id, session, 2, ApplicationActorType.ADMIN) }
        producer.clear()

        publisher().publishDue()

        val all = sent().single()
        assertEquals("ALL", all["scope"].asString())
        assertEquals("ADMIN", all["cancelledBy"].asString())
    }

    @Test
    fun `a session addition that went on to be cancelled is not announced as confirmed`() {
        val session = preregisterSession()
        val added = preregister.insert(EXPO, session, 2, 2, "CONFIRMED")
        record { sessionAdded(EXPO, "req-1", session, 2, "CONFIRMED", added.id) }
        preregister.status(added.id, "CANCELLED", ApplicationActorType.USER, 2)

        publisher().publishDue()

        assertEquals(0, sent().size)
        assertEquals(1L, count("skip_reason = 'STATE_CHANGED'"))
    }

    @Test
    fun `a confirmed session addition lists the participant with the confirmed result`() {
        val session = preregisterSession()
        val added = preregister.insert(EXPO, session, 2, 3, "CONFIRMED")
        record { sessionAdded(EXPO, "req-2", session, 3, "CONFIRMED", added.id) }

        publisher().publishDue()

        val event = sent().single()
        assertEquals("PREREGISTER_SESSION_ADDED", event["type"].asString())
        assertEquals("CONFIRMED", event["items"].single()["result"].asString())
        assertEquals("홍길순", event["items"].single()["participantName"].asString())
    }

    @Test
    fun `nothing is recorded when disabled or for an expo outside the allowed list`() {
        val id = application()
        transactions.executeWithoutResult {
            ApplicationNotifications(repository, false, emptyList()).programAdmitted(EXPO, id, "CONFIRMED")
            ApplicationNotifications(repository, true, listOf(UUID.randomUUID().toString())).programAdmitted(EXPO, id, "CONFIRMED")
        }
        assertEquals(0L, count())
    }

    @Test
    fun `publisher skips an expo that is not on its allowed list`() {
        val id = application()
        record { programAdmitted(EXPO, id, "CONFIRMED") }

        publisher(listOf(UUID.randomUUID().toString())).publishDue()

        assertEquals(0, sent().size)
        assertNull(jdbc.queryForObject("SELECT delivered_at FROM tb_application_notification", java.sql.Timestamp::class.java))
    }

    @Test
    fun `a rolled back state change leaves no notification and recording requires the same transaction`() {
        val id = application()
        transactions.executeWithoutResult { status ->
            notifications.programAdmitted(EXPO, id, "CONFIRMED")
            status.setRollbackOnly()
        }
        assertEquals(0L, count())
        assertThrows(IllegalTransactionStateException::class.java) { managed.programAdmitted(EXPO, id, "CONFIRMED") }
    }

    @Test
    fun `rows older than the activation instant are never collected`() {
        val id = application()
        record { programAdmitted(EXPO, id, "CONFIRMED") }
        jdbc.update("UPDATE tb_application_notification SET occurred_at = '2019-12-31T00:00:00Z'")

        publisher().publishDue()

        assertTrue(sent().isEmpty())
    }

    companion object {
        private val EXPO = UUID.fromString("cf22c389-eeb4-462f-9c9a-b216bc046f10")

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
