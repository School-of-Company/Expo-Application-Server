package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.MapPropertySource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramPromotionRepository
import team.startup.application.domain.application.service.impl.ProgramPromotionKafkaConfig
import team.startup.application.domain.application.service.impl.ProgramPromotionPolicy
import team.startup.application.domain.application.service.impl.ProgramPromotionPublisher
import team.startup.application.global.scheduling.DefaultTaskSchedulerConfig
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
@Import(ProgramPromotionTests.FixedClock::class)
class ProgramPromotionTests {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var repository: ProgramPromotionRepository

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var mvc: MockMvc

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE tb_program_session_application CASCADE")
        userMode = "valid"
        expoMode = "valid"
        requestedIds.clear()
        callback = {}
        text = emptyMap()
    }

    private fun application(
        promoted: Boolean = true,
        type: ProgramSessionType = ProgramSessionType.STANDARD,
    ): ProgramPromotionEvent {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id,expo_id,program_type,program_id,group_id,participant_id,trainee_id,status," +
                "definition_snapshot,form_id,form_version,questions_snapshot,answers_snapshot) " +
                "VALUES (?, ?, ?, 3, ?, 2, 9, 'CONFIRMED', '{}', ?, 1, '[]', '{}')",
            id,
            EXPO,
            type.name,
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        if (promoted) jdbc.update("INSERT INTO tb_program_session_history(application_id,status) VALUES (?, 'WAITING')", id)
        jdbc.update("INSERT INTO tb_program_session_history(application_id,status) VALUES (?, 'CONFIRMED')", id)
        jdbc.update("INSERT INTO tb_program_session_event(application_id,status) VALUES (?, 'CONFIRMED')", id)
        val eventId = jdbc.queryForObject("SELECT event_id FROM tb_program_session_event WHERE application_id = ?", UUID::class.java, id)!!
        return ProgramPromotionEvent(1, eventId, id, EXPO, 2, type, 3)
    }

    private fun check(
        event: ProgramPromotionEvent,
        token: String = "test-token",
    ) = mvc.perform(
        post(
            "/internal/program-promotions/check",
        ).header("X-Internal-Token", token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(event)),
    )

    @Test
    fun `fixed activation cutoff excludes historical backlog and survives publisher restart`() {
        assertThrows(IllegalArgumentException::class.java) { ProgramPromotionPolicy(true, "") }
        val old = application()
        val current = application()
        jdbc.update(
            "UPDATE tb_program_session_event SET created_at = ? WHERE event_id = ?",
            java.sql.Timestamp.from(CUTOFF.minusSeconds(1)),
            old.eventId,
        )
        jdbc.update(
            "UPDATE tb_program_session_event SET created_at = ? WHERE event_id = ?",
            java.sql.Timestamp.from(CUTOFF),
            current.eventId,
        )
        assertEquals(listOf(current.eventId), repository.due(CUTOFF))
        check(old).andExpect(jsonPath("$.reason").value("NOT_CURRENT_PROMOTION"))
        repository.publish(old.eventId, CUTOFF) { fail("historical send") }
        val producer = MockProducer<String, String>(true, null, StringSerializer(), StringSerializer())
        ProgramPromotionPublisher(repository, producer, mapper, ProgramPromotionPolicy(true, CUTOFF.toString())).publishDue()
        assertEquals(current.eventId.toString(), producer.history().single().key())
        assertNull(
            jdbc.queryForObject(
                "SELECT delivered_at FROM tb_program_session_event WHERE event_id = ?",
                java.sql.Timestamp::class.java,
                old.eventId,
            ),
        )
    }

    @Test
    fun `promotion classification is persisted and initial confirmations stay outside due index`() {
        val initial = application(false)
        val promoted = application()
        assertEquals(
            false,
            jdbc.queryForObject(
                "SELECT is_promotion FROM tb_program_session_event WHERE event_id = ?",
                Boolean::class.java,
                initial.eventId,
            ),
        )
        assertEquals(
            true,
            jdbc.queryForObject(
                "SELECT is_promotion FROM tb_program_session_event WHERE event_id = ?",
                Boolean::class.java,
                promoted.eventId,
            ),
        )
        jdbc.update("DELETE FROM tb_program_session_history")
        assertEquals(listOf(promoted.eventId), repository.due(CUTOFF))
        assertEquals(listOf(promoted.eventId), repository.due(CUTOFF))
        val index =
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ix_program_promotion_due'",
                String::class.java,
            )!!
        assertTrue(index.contains("is_promotion"))
    }

    @Test
    fun `V15 upgrade classifies existing promotions without making initial confirmations eligible`() {
        val schema = "promotion_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val source = requireNotNull(jdbc.dataSource)
        Flyway
            .configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("14")
            .load()
            .migrate()
        try {
            source.connection.use { connection ->
                connection.schema = schema
                try {
                    val scoped = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    for (promoted in listOf(false, true)) {
                        val id = UUID.randomUUID()
                        scoped.update(
                            "INSERT INTO tb_program_session_application " +
                                "(id,expo_id,program_type,program_id,group_id,participant_id,status," +
                                "definition_snapshot,form_id,form_version,questions_snapshot,answers_snapshot) " +
                                "VALUES (?, ?, 'STANDARD', 3, ?, 2, 'CONFIRMED', '{}', ?, 1, '[]', '{}')",
                            id,
                            EXPO,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                        )
                        if (promoted) {
                            scoped.update(
                                "INSERT INTO tb_program_session_history(application_id,status) VALUES (?, 'WAITING')",
                                id,
                            )
                        }
                        scoped.update("INSERT INTO tb_program_session_history(application_id,status) VALUES (?, 'CONFIRMED')", id)
                        scoped.update("INSERT INTO tb_program_session_event(application_id,status) VALUES (?, 'CONFIRMED')", id)
                    }
                    Flyway
                        .configure()
                        .dataSource(source)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                        .migrate()
                    assertEquals(
                        1,
                        scoped.queryForObject("SELECT count(*) FROM tb_program_session_event WHERE is_promotion", Int::class.java),
                    )
                    assertEquals(
                        1,
                        scoped.queryForObject("SELECT count(*) FROM tb_program_session_event WHERE NOT is_promotion", Int::class.java),
                    )
                } finally {
                    connection.schema = "public"
                }
            }
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
    }

    @Test
    fun `blocked Kafka ACK does not occupy the default scheduler`() {
        application()
        val producer = MockProducer<String, String>(false, null, StringSerializer(), StringSerializer())
        val heartbeat = Heartbeat()
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "scheduler-test",
                    mapOf("application.promotions.enabled" to "true"),
                ),
            )
            context.register(SchedulerConfiguration::class.java)
            context.registerBean(
                "taskScheduler",
                ThreadPoolTaskScheduler::class.java,
                Supplier { DefaultTaskSchedulerConfig().taskScheduler() },
            )
            context.registerBean(
                "programPromotionScheduler",
                ThreadPoolTaskScheduler::class.java,
                Supplier {
                    ProgramPromotionKafkaConfig().programPromotionScheduler()
                },
            )
            context.registerBean(
                "publisher",
                ProgramPromotionPublisher::class.java,
                Supplier {
                    ProgramPromotionPublisher(repository, producer, mapper, POLICY)
                },
            )
            context.registerBean("heartbeat", Heartbeat::class.java, Supplier { heartbeat })
            context.refresh()
            try {
                assertTrue(context.containsBean("publisher"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (producer.history().isEmpty() && System.nanoTime() < deadline) Thread.yield()
                assertEquals(1, producer.history().size)
                heartbeat.pulses = CountDownLatch(3)
                assertTrue(heartbeat.pulses.await(2, TimeUnit.SECONDS))
            } finally {
                producer.completeNext()
            }
        }
    }

    @TestConfiguration
    @EnableScheduling
    class SchedulerConfiguration

    @TestConfiguration
    class FixedClock {
        @Bean @Primary
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)
    }

    class Heartbeat {
        @Volatile var pulses = CountDownLatch(3)

        @Scheduled(fixedDelay = 20)
        fun tick() {
            pulses.countDown()
        }
    }

    @Test
    fun `past session is not current and uppercase Expo UUID is equivalent`() {
        val event = application()
        expoMode = "past"
        check(event).andExpect(status().isOk).andExpect(jsonPath("$.reason").value("NOT_CURRENT_PROMOTION"))
        expoMode = "upper-expo"
        check(event).andExpect(status().isOk).andExpect(jsonPath("$.eligible").value(true))
    }

    @Test
    fun `real broker ACK publishes durable promotion JSON that consumer can read`() {
        val event = application()
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to kafka.bootstrapServers,
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to StringSerializer::class.java,
                "acks" to "all",
            ),
        ).use { producer ->
            ProgramPromotionPublisher(repository, producer, mapper, POLICY).publishDue()
        }
        assertTrue(repository.due(CUTOFF).isEmpty())
        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to kafka.bootstrapServers,
                "group.id" to UUID.randomUUID().toString(),
                "auto.offset.reset" to "earliest",
                "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            ),
        ).use { consumer ->
            consumer.subscribe(listOf("application.program-session.promoted"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var found = false
            while (!found && System.nanoTime() < deadline) {
                found =
                    consumer.poll(Duration.ofSeconds(1)).any {
                        it.key() == event.eventId.toString() &&
                            mapper.readValue(it.value(), ProgramPromotionEvent::class.java) == event
                    }
            }
            assertTrue(found)
        }
    }

    @Test
    fun `only saved promotions publish and timestamps do not determine transition order`() {
        application(false)
        val promoted = application()
        jdbc.update(
            "UPDATE tb_program_session_history SET created_at = CASE WHEN status = 'WAITING' THEN '2030-01-01'::timestamptz ELSE '2020-01-01'::timestamptz END",
        )
        val producer = MockProducer<String, String>(true, null, StringSerializer(), StringSerializer())
        ProgramPromotionPublisher(repository, producer, mapper, POLICY).publishDue()
        assertEquals(1, producer.history().size)
        assertEquals("application.program-session.promoted", producer.history().single().topic())
        assertEquals(promoted.eventId.toString(), producer.history().single().key())
        assertEquals(promoted, mapper.readValue(producer.history().single().value(), ProgramPromotionEvent::class.java))
        assertTrue(repository.due(CUTOFF).isEmpty())
    }

    @Test
    fun `ACK is required and failure retains stable event for restart retry`() {
        val event = application()
        val producer = MockProducer<String, String>(false, null, StringSerializer(), StringSerializer())
        val publisher = ProgramPromotionPublisher(repository, producer, mapper, POLICY)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit { publisher.publishDue() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (producer.history().isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, producer.history().size)
            assertNull(
                jdbc.queryForObject(
                    "SELECT delivered_at FROM tb_program_session_event WHERE event_id = ?",
                    java.sql.Timestamp::class.java,
                    event.eventId,
                ),
            )
            producer.errorNext(RuntimeException("broker unavailable"))
            task.get(5, TimeUnit.SECONDS)
            assertEquals(
                1,
                jdbc.queryForObject("SELECT attempts FROM tb_program_session_event WHERE event_id = ?", Int::class.java, event.eventId),
            )
            assertTrue(repository.due(CUTOFF).isEmpty())
            jdbc.update("UPDATE tb_program_session_event SET next_attempt_at = clock_timestamp()")
            val retry = MockProducer<String, String>(true, null, StringSerializer(), StringSerializer())
            ProgramPromotionPublisher(repository, retry, mapper, POLICY).publishDue()
            assertEquals(producer.history().single().value(), retry.history().single().value())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `successful send with failed completion write replays identical event`() {
        val event = application()
        jdbc.execute("ALTER TABLE tb_program_session_event ADD CONSTRAINT test_no_completion CHECK (delivered_at IS NULL)")
        val producer = MockProducer<String, String>(true, null, StringSerializer(), StringSerializer())
        try {
            ProgramPromotionPublisher(repository, producer, mapper, POLICY).publishDue()
            assertEquals(1, producer.history().size)
            assertEquals(event, repository.find(event.applicationId)!!.event)
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_event DROP CONSTRAINT test_no_completion")
        }
        jdbc.update("UPDATE tb_program_session_event SET next_attempt_at = clock_timestamp()")
        ProgramPromotionPublisher(repository, producer, mapper, POLICY).publishDue()
        assertEquals(2, producer.history().size)
        assertEquals(producer.history()[0].value(), producer.history()[1].value())
    }

    @Test
    fun `concurrent publishers cannot claim same event`() {
        val event = application()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task =
                executor.submit {
                    repository.publish(event.eventId, CUTOFF) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repository.publish(event.eventId, CUTOFF) { fail("duplicate send") }
            release.countDown()
            task.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `standard and training companion check uses representative own phone and participant name`() {
        for (type in ProgramSessionType.entries) {
            val event = application(type = type)
            check(event)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.eventId").value(event.eventId.toString()))
                .andExpect(jsonPath("$.representativePhoneNumber").value("01011112222"))
                .andExpect(jsonPath("$.participantName").value("Companion"))
                .andExpect(jsonPath("$.programName").value("Program"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-13T10:00:00+09:00"))
                .andExpect(jsonPath("$.endedAt").value("2026-10-13T11:00:00+09:00"))
                .andExpect(jsonPath("$.location").value("Hall"))
        }
        assertEquals(listOf(2L, 1L, 2L, 1L), requestedIds)
    }

    @Test
    fun `actual absent representative phone is null without companion fallback`() {
        val event = application()
        userMode = "no-phone"
        check(event).andExpect(status().isOk).andExpect(jsonPath("$.representativePhoneNumber").isEmpty)
    }

    @Test
    fun `initial admission old event cancellation and reapply are ineligible`() {
        check(application(false)).andExpect(jsonPath("$.reason").value("NOT_CURRENT_PROMOTION"))
        val event = application()
        check(event.copy(eventId = UUID.randomUUID())).andExpect(jsonPath("$.reason").value("NOT_CURRENT_PROMOTION"))
        jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", event.applicationId)
        check(event).andExpect(jsonPath("$.reason").value("CANCELLED"))
        val next = application()
        assertNotEquals(event.eventId, next.eventId)
        check(next).andExpect(jsonPath("$.eligible").value(true))
    }

    @Test
    fun `cancellation during remote lookup is rechecked`() {
        val event = application()
        callback = { jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", event.applicationId) }
        check(event).andExpect(jsonPath("$.reason").value("CANCELLED"))
    }

    @Test
    fun `check requires internal token validates version safe IDs and identity`() {
        val event = application()
        check(event, "wrong").andExpect(status().isUnauthorized)
        check(event.copy(version = 2)).andExpect(status().isBadRequest)
        check(event.copy(participantId = 9007199254740992)).andExpect(status().isBadRequest)
        check(event.copy(programId = 4)).andExpect(status().isConflict)
    }

    @Test
    fun `User outages missing identity and malformed response never become absent phone`() {
        val event = application()
        for (mode in listOf("outage", "missing", "wrong-id", "malformed", "absent-phone")) {
            userMode = mode
            check(event).andExpect(status().is5xxServerError)
        }
    }

    @Test
    fun `Expo outages malformed identity and invalid session times are errors`() {
        val event = application()
        for (mode in listOf("outage", "missing", "wrong-id", "malformed", "invalid-time", "reversed", "missing-title")) {
            expoMode = mode
            check(event).andExpect(status().is5xxServerError)
        }
    }

    @Test
    fun `notice text is normalized to Notification labels without rewriting other characters`() {
        val event = application()
        text =
            mapOf(
                "name" to " Com\u200Dpanion\n",
                "title" to "\tA\r\nB\u200B\u2028C\u00A0D\uDB40\uDC01 ",
                "place" to "1층\n강당\u2029",
            )
        check(event)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eligible").value(true))
            .andExpect(jsonPath("$.participantName").value("Companion"))
            .andExpect(jsonPath("$.programName").value("A B C\u00A0D"))
            .andExpect(jsonPath("$.location").value("1층 강당"))
    }

    @Test
    fun `notice lengths are UTF-16 units like Notification`() {
        val event = application()
        val pair = "\uD840\uDC00"
        text = mapOf("name" to pair.repeat(50), "title" to "a".repeat(100), "place" to pair.repeat(127) + "a")
        check(event)
            .andExpect(jsonPath("$.participantName").value(pair.repeat(50)))
            .andExpect(jsonPath("$.programName").value("a".repeat(100)))
            .andExpect(jsonPath("$.location").value(pair.repeat(127) + "a"))
        for (place in listOf(pair.repeat(128), "a".repeat(256), " \u200B\n ")) {
            text = mapOf("place" to place)
            check(event).andExpect(jsonPath("$.eligible").value(true)).andExpect(jsonPath("$.location").isEmpty)
        }
    }

    @Test
    fun `unsendable name or title skips the notice instead of failing the check`() {
        val event = application()
        for (value in listOf(
            mapOf("name" to "\uD840\uDC00".repeat(51)),
            mapOf("title" to "a".repeat(101)),
            mapOf("name" to " \u200B\r\n"),
            mapOf("title" to ""),
        )) {
            text = value
            check(event)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_CURRENT_PROMOTION"))
        }
    }

    companion object {
        private val CUTOFF = Instant.parse("2020-01-01T00:00:00Z")
        private val POLICY = ProgramPromotionPolicy(false, CUTOFF.toString())
        private val EXPO = UUID.fromString("cf22c389-eeb4-462f-9c9a-b216bc046f10")

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @Container @JvmStatic
        val kafka = KafkaContainer("apache/kafka:3.9.1")

        @Volatile private var userMode = "valid"

        @Volatile private var expoMode = "valid"

        @Volatile private var callback: () -> Unit = {}

        // Overrides name/title/place, escaped so control characters reach the gateway intact.
        @Volatile private var text: Map<String, String> = emptyMap()

        private fun json(
            field: String,
            default: String,
        ) = (text[field] ?: default)
            .map {
                if (it in ' '..'~' && it != '"' &&
                    it != '\\'
                ) {
                    "$it"
                } else {
                    "\\u%04x".format(it.code)
                }
            }.joinToString("", "\"", "\"")

        private val requestedIds = java.util.concurrent.CopyOnWriteArrayList<Long>()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal") { exchange ->
                    val user = exchange.requestURI.path == "/internal/standard-participants/details"
                    val mode = if (user) userMode else expoMode
                    val code =
                        when {
                            exchange.requestHeaders.getFirst("X-Internal-Token") != "supplier-token" -> 401

                            mode == "outage" -> 503

                            mode ==
                                "missing" -> 404

                            else -> 200
                        }
                    val body =
                        if (user) {
                            val request = exchange.requestBody.bufferedReader().readText()
                            val id = if (request.contains("[2]")) 2L else 1L
                            requestedIds.add(id)
                            val phone = if (mode == "no-phone") "null" else "\"${if (id == 1L) "010-1111-2222" else "01099998888"}\""
                            """[{"participantId":${if (mode == "wrong-id") 99 else id},"name":${json(
                                "name",
                                if (id == 2L) "Companion" else "Representative",
                            )},"representativeId":${if (id == 2L) 1 else "null"}${if (mode == "absent-phone") "" else ",\"phoneNumber\":$phone"},"notificationPhoneNumber":"01099998888"}]"""
                        } else {
                            callback()
                            val separator = if (exchange.requestURI.path.endsWith("batch")) " " else "T"
                            val program = """{"id":${if (mode == "wrong-id") 99 else 3},"expoId":"$EXPO","programType":"${if (exchange.requestURI.path
                                    .endsWith(
                                        "batch",
                                    )
                            ) {
                                "TRAINING"
                            } else {
                                "STANDARD"
                            }}","title":${if (mode == "missing-title") {
                                "null"
                            } else {
                                json(
                                    "title",
                                    "Program",
                                )
                            }},"startedAt":"2026-10-13${separator}10:00","endedAt":"2026-10-13$separator${if (mode == "reversed") "09" else "11"}:00","place":${json(
                                "place",
                                "Hall",
                            )}}"""
                            val response =
                                when (mode) {
                                    "past" -> program.replace("2026-10-13", "2020-01-01")
                                    "upper-expo" -> program.replace(EXPO.toString(), EXPO.toString().uppercase())
                                    "invalid-time" -> program.replace("10:00", "invalid")
                                    else -> program
                                }
                            if (exchange.requestURI.path.endsWith("batch")) "[$response]" else response
                        }
                    val bytes = (if (mode == "malformed") "bad json" else body).toByteArray()
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.promotions.publish-from") { CUTOFF.toString() }
            registry.add("application.registration.user-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.user-internal-token") { "supplier-token" }
            registry.add("application.registration.expo-internal-token") { "supplier-token" }
        }
    }
}
