package team.startup.application

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.ExpoProjectionReadTests.Companion.event
import team.startup.application.domain.application.repository.ExpoProjectionRepository
import team.startup.application.domain.application.service.impl.AdminGroupMessageGateway
import team.startup.application.domain.application.service.impl.ExpoChangedConsumer
import team.startup.application.domain.application.service.impl.ExpoProjectionUpdater
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

// KAFKA_EXPO_PROJECTION_ENABLED=true로 실제 broker와 PostgreSQL에서 version 순서·중복·tombstone·DLT와 읽기 연결을 확인한다.
@SpringBootTest(properties = ["eureka.client.enabled=false", "application.expo-projection.enabled=true"])
@Testcontainers
class ExpoProjectionKafkaTests {
    @Autowired private lateinit var updater: ExpoProjectionUpdater

    @Autowired private lateinit var repository: ExpoProjectionRepository

    @Autowired private lateinit var registration: RegistrationGateway

    @Autowired private lateinit var groupMessage: AdminGroupMessageGateway

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    private fun change(
        expoId: UUID,
        version: Long,
        title: String = "v$version",
        deadline: String? = "2030-01-01T01:30:00Z",
        deleting: Boolean = false,
        eventId: UUID = UUID.randomUUID(),
    ) = ExpoChangedConsumer.parse(
        mapper,
        expoId.toString(),
        event(version = version, title = title, cancelEndedAt = deadline, deleting = deleting, expoId = expoId, eventId = eventId),
    )

    private fun tombstone(
        expoId: UUID,
        version: Long,
    ) = ExpoProjectionRepository.Change(UUID.randomUUID(), version, expoId, null, emptyList())

    private fun sessionRows(expoId: UUID) =
        jdbc.queryForObject("SELECT count(*) FROM tb_expo_projection_session WHERE expo_id = ?", Long::class.java, expoId)

    @Test
    fun `only a newer version replaces the whole projection and duplicates are ignored`() {
        val expo = UUID.randomUUID()
        val first = change(expo, 10)

        assertTrue(updater.apply(first))
        assertFalse(updater.apply(first))
        assertFalse(updater.apply(change(expo, 10, title = "same version, other event")))
        assertFalse(updater.apply(change(expo, 5, title = "late old event")))
        assertEquals("v10", repository.expo(expo)?.title)
        assertEquals(Instant.parse("2030-01-01T01:30:00Z"), repository.session(expo, 7)?.cancelEndedAt)

        assertTrue(updater.apply(change(expo, 11, deadline = null)))
        assertEquals("v11", repository.expo(expo)?.title)
        assertEquals(ExpoProjectionRepository.Session(7, null), repository.session(expo, 7))
        assertEquals(11L, repository.version(expo))
    }

    @Test
    fun `tombstone removes the projection and keeps its version against resurrection`() {
        val expo = UUID.randomUUID()
        assertTrue(updater.apply(change(expo, 20)))

        assertTrue(updater.apply(tombstone(expo, 21)))
        assertNull(repository.expo(expo))
        assertNull(repository.session(expo, 7))
        assertEquals(0L, sessionRows(expo))
        assertEquals(21L, repository.version(expo))

        assertFalse(updater.apply(change(expo, 20)))
        assertFalse(updater.apply(tombstone(expo, 21)))
        assertNull(repository.expo(expo))
    }

    @Test
    fun `deleting expo is a projection miss`() {
        val expo = UUID.randomUUID()
        assertTrue(updater.apply(change(expo, 30, deleting = true)))

        assertNull(repository.expo(expo))
        assertNull(repository.session(expo, 7))
    }

    @Test
    fun `title reads the projection while expo period stays on the sync call`() {
        val expo = UUID.randomUUID()
        assertTrue(updater.apply(change(expo, 40)))

        assertEquals("v40", groupMessage.expoName(expo))
        // No Expo is reachable in this test, so every synchronous call fails with its existing 503,
        // including the expo period even though the projection holds a row for it.
        for (target in listOf(expo, UUID.randomUUID())) {
            val failure = assertThrows(ResponseStatusException::class.java) { registration.expoPeriod(target.toString()) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.statusCode)
        }
    }

    @Test
    fun `consumer applies broker records in version order and sends invalid ones to the DLT`() {
        val expo = UUID.randomUUID()
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to kafka.bootstrapServers,
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to StringSerializer::class.java,
                "acks" to "all",
            ),
        ).use { producer ->
            fun send(value: String) = producer.send(ProducerRecord(ExpoChangedConsumer.TOPIC, expo.toString(), value)).get()
            send(event(version = 2, title = "second", expoId = expo))
            send(event(version = 1, title = "first", expoId = expo))
            send("{\"schemaVersion\":1,\"expoId\":\"$expo\"}")
            send(event(version = 3, title = "third", expoId = expo))
        }
        await { repository.expo(expo)?.title == "third" }
        assertEquals(3L, repository.version(expo))

        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to kafka.bootstrapServers,
                "group.id" to UUID.randomUUID().toString(),
                "auto.offset.reset" to "earliest",
                "key.deserializer" to StringDeserializer::class.java,
                "value.deserializer" to StringDeserializer::class.java,
            ),
        ).use { consumer ->
            consumer.subscribe(listOf("${ExpoChangedConsumer.TOPIC}.dlt"))
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            var found = false
            while (!found && System.nanoTime() < deadline) {
                found = consumer.poll(Duration.ofSeconds(1)).any { it.key() == expo.toString() && !it.value().contains("\"version\"") }
            }
            assertTrue(found)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met in time")
            Thread.sleep(100)
        }
    }

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @Container @JvmStatic
        val kafka = KafkaContainer("apache/kafka:3.9.1")

        @BeforeAll @JvmStatic
        fun topics() {
            AdminClient.create(mapOf<String, Any>("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin
                    .createTopics(
                        listOf(
                            NewTopic(ExpoChangedConsumer.TOPIC, 1, 1.toShort()),
                            NewTopic("${ExpoChangedConsumer.TOPIC}.dlt", 1, 1.toShort()),
                        ),
                    ).all()
                    .get()
            }
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.expo-projection.bootstrap-servers") { kafka.bootstrapServers }
        }
    }
}
