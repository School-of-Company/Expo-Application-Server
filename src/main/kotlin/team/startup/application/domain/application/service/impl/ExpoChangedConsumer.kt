package team.startup.application.domain.application.service.impl

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.RetryListener
import org.springframework.kafka.support.ExceptionMatcher
import org.springframework.stereotype.Component
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff
import team.startup.application.domain.application.repository.ExpoProjectionRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.time.OffsetDateTime
import java.util.UUID

// Expo `expo.expo.changed` 소비(docs/expo-projection.md). 실패 레코드는 `<topic>.dlt`로 보내며, Expo StandardCreated 소비자의
// 재시도·DLT 규칙을 따른다: 일시적 DB 장애만 1·2·4·8·10초로 5번 다시 시도하고 나머지(잘못된 메시지 포함)는 바로 DLT다.
@Configuration
@EnableKafka
@ConditionalOnProperty(name = ["application.expo-projection.enabled"], havingValue = "true")
class ExpoProjectionKafkaConfig(
    @Value("\${application.expo-projection.bootstrap-servers:}") private val servers: String,
    @Value("\${application.expo-projection.group-id:expo-application-server.expo-changed}") private val groupId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(
            servers.isNotBlank(),
        ) { "KAFKA_EXPO_PROJECTION_BOOTSTRAP_SERVERS must be configured when KAFKA_EXPO_PROJECTION_ENABLED=true" }
        require(groupId.isNotBlank()) { "KAFKA_EXPO_PROJECTION_GROUP_ID must not be blank" }
    }

    @Bean
    fun expoProjectionDltTemplate(): KafkaTemplate<String, String> =
        KafkaTemplate(
            DefaultKafkaProducerFactory(
                mapOf<String, Any>(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to servers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to 5000,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 10000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 30000,
                ),
            ),
        )

    @Bean
    fun expoProjectionContainerFactory(
        expoProjectionDltTemplate: KafkaTemplate<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val recoverer =
            DeadLetterPublishingRecoverer(expoProjectionDltTemplate) { record, _ -> TopicPartition(record.topic() + ".dlt", -1) }
        recoverer.setFailIfSendResultIsError(true)
        val backOff =
            ExponentialBackOff(1_000L, 2.0).apply {
                maxInterval = 10_000L
                maxAttempts = 5L
            }
        val handler = DefaultErrorHandler(recoverer, backOff)
        val retryable =
            ExceptionMatcher
                .forAllowList()
                .add(TransientDataAccessException::class.java)
                .add(DataAccessResourceFailureException::class.java)
                .add(CannotCreateTransactionException::class.java)
                .add(SQLTransientException::class.java)
                .add(SQLRecoverableException::class.java)
                .traverseCauses(true)
                .build()
        handler.addNotRetryableExceptions(IllegalArgumentException::class.java)
        handler.setBackOffFunction { _, exception ->
            val connectionFailure =
                generateSequence<Throwable>(exception) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState?.startsWith("08") == true || it.sqlState in setOf("57P01", "57P02", "57P03") }
            if (retryable.match(exception) || connectionFailure) backOff else FixedBackOff(0L, 0L)
        }
        handler.setResetStateOnExceptionChange(false)
        handler.setResetStateOnRecoveryFailure(false)
        handler.setRetryListeners(
            object : RetryListener {
                override fun failedDelivery(
                    record: ConsumerRecord<*, *>,
                    exception: Exception?,
                    attempt: Int,
                ) {
                    log.warn(
                        "Expo changed delivery failed: partition={}, offset={}, attempt={}, type={}",
                        record.partition(),
                        record.offset(),
                        attempt,
                        exception?.cause?.javaClass?.simpleName ?: exception?.javaClass?.simpleName,
                    )
                }

                override fun recoveryFailed(
                    record: ConsumerRecord<*, *>,
                    original: Exception?,
                    failure: Exception,
                ) {
                    log.error(
                        "Expo changed DLT publication failed: partition={}, offset={}, type={}",
                        record.partition(),
                        record.offset(),
                        failure.javaClass.simpleName,
                    )
                }
            },
        )
        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(
                DefaultKafkaConsumerFactory(
                    mapOf<String, Any>(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to servers,
                        ConsumerConfig.GROUP_ID_CONFIG to groupId,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ),
                ),
            )
            setCommonErrorHandler(handler)
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            containerProperties.isSyncCommits = true
        }
    }
}

@Component
@ConditionalOnProperty(name = ["application.expo-projection.enabled"], havingValue = "true")
class ExpoProjectionUpdater(
    private val repository: ExpoProjectionRepository,
) {
    @Transactional
    fun apply(change: ExpoProjectionRepository.Change): Boolean = repository.apply(change)
}

@Component
@ConditionalOnProperty(name = ["application.expo-projection.enabled"], havingValue = "true")
class ExpoChangedConsumer(
    private val mapper: ObjectMapper,
    private val updater: ExpoProjectionUpdater,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [TOPIC], containerFactory = "expoProjectionContainerFactory")
    fun consume(record: ConsumerRecord<String?, String?>) {
        val change =
            try {
                parse(mapper, record.key(), record.value())
            } catch (_: Exception) {
                // Parser exceptions can carry the payload; keep only the fact that the message was rejected.
                throw IllegalArgumentException("Invalid expo changed event")
            }
        if (!updater.apply(change)) {
            log.info(
                "Expo changed event skipped as not newer: partition={}, offset={}, version={}",
                record.partition(),
                record.offset(),
                change.version,
            )
        }
    }

    companion object {
        const val TOPIC = "expo.expo.changed"
        private const val SCHEMA_VERSION = 1L

        // 모르는 필드는 무시한다. schemaVersion이 다르면(의미가 바뀐 형식) 반영하지 않고 DLT로 보낸다.
        fun parse(
            mapper: ObjectMapper,
            key: String?,
            value: String?,
        ): ExpoProjectionRepository.Change {
            val body = mapper.readTree(requireNotNull(value))
            require(body.isObject && long(body, "schemaVersion") == SCHEMA_VERSION)
            val eventId = requireNotNull(ExpoProjection.uuidOrNull(text(body, "eventId")))
            val version = long(body, "version")
            require(version > 0)
            val expoIdText = text(body, "expoId")
            val expoId = requireNotNull(ExpoProjection.uuidOrNull(expoIdText))
            require(key == expoIdText)
            val deleted = body.get("deleted")
            require(deleted != null && deleted.isBoolean)
            if (deleted.booleanValue()) return ExpoProjectionRepository.Change(eventId, version, expoId, null, emptyList())
            val expo = body.get("expo")
            require(expo != null && expo.isObject)
            val revision = long(expo, "participantPhoneVerificationRevision")
            require(revision >= 0)
            val sessionNodes = body.get("preregisterSessions")
            require(sessionNodes != null && sessionNodes.isArray)
            val sessions =
                (0 until sessionNodes.size()).map { index ->
                    val node = sessionNodes.get(index)
                    require(node.isObject)
                    val id = long(node, "id")
                    require(id > 0)
                    val deadline = node.get("cancelEndedAt")
                    require(deadline != null && (deadline.isNull || deadline.isString))
                    ExpoProjectionRepository.Session(
                        id,
                        deadline.takeUnless { it.isNull }?.let { OffsetDateTime.parse(it.stringValue()).toInstant() },
                    )
                }
            require(sessions.map { it.id }.distinct().size == sessions.size)
            return ExpoProjectionRepository.Change(
                eventId,
                version,
                expoId,
                ExpoProjectionRepository.Expo(
                    title = text(expo, "title"),
                    startedDay = text(expo, "startedDay"),
                    finishedDay = text(expo, "finishedDay"),
                    participantPhoneVerificationRequired = bool(expo, "participantPhoneVerificationRequired"),
                    participantPhoneVerificationRevision = revision,
                    deleting = bool(expo, "deleting"),
                ),
                sessions,
            )
        }

        private fun text(
            node: JsonNode,
            field: String,
        ): String = requireNotNull(node.get(field)?.takeIf { it.isString }).stringValue()

        private fun bool(
            node: JsonNode,
            field: String,
        ): Boolean = requireNotNull(node.get(field)?.takeIf { it.isBoolean }).booleanValue()

        private fun long(
            node: JsonNode,
            field: String,
        ): Long = requireNotNull(node.get(field)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }).longValue()
    }
}
