package team.startup.application.domain.application.service.impl

import com.fasterxml.jackson.annotation.JsonInclude
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.ApplicationNotificationRepository
import team.startup.application.domain.application.repository.ApplicationNotificationRepository.Batch
import team.startup.application.domain.application.repository.withDeliveryLock
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

// Notification `notification.sms.requested` v1의 신청 결과 유형(Notification #110, docs/notification-110-application-result-sms.md).
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationNotificationEvent(
    val eventId: UUID,
    val version: Int = 1,
    val type: String,
    val phoneNumber: String,
    val expoId: UUID,
    val senderType: String,
    val occurredAt: String,
    val items: List<ApplicationNotificationItem>,
    val cancelledBy: String? = null,
    val scope: String? = null,
)

@Configuration
@ConditionalOnProperty(name = ["application.notifications.enabled"], havingValue = "true")
class ApplicationNotificationKafkaConfig {
    @Bean
    fun applicationNotificationScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply { setThreadNamePrefix("application-notification-") }

    @Bean(destroyMethod = "close")
    fun applicationNotificationProducer(
        @Value("\${application.notifications.bootstrap-servers:}") servers: String,
    ): Producer<String, String> {
        require(servers.isNotBlank()) { "APPLICATION_NOTIFICATIONS_BOOTSTRAP_SERVERS must be configured" }
        return KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to servers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to 5000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 10000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 30000,
            ),
        )
    }
}

/**
 * #308: 커밋된 outbox 행을 Notification으로 발행한다. 보내기 직전에 현재 상태를 다시 확인하고(취소된 신청의 확정 안내 방지),
 * eventId는 행의 안정 키에서 만들어 재시도·재발행에도 같다. 문자 실패나 발행 실패는 신청·취소를 되돌리지 않는다.
 * delivered_at은 Kafka ACK이지 문자 수신이 아니다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["application.notifications.enabled"], havingValue = "true")
class ApplicationNotificationPublisher(
    private val repository: ApplicationNotificationRepository,
    private val resolver: ApplicationNotificationResolver,
    private val jdbc: JdbcTemplate,
    @Qualifier("applicationNotificationProducer") private val producer: Producer<String, String>,
    private val mapper: ObjectMapper,
    @Value("\${application.notifications.publish-from:}") publishFrom: String,
    @Value("\${application.notifications.topic:notification.sms.requested}") private val topic: String,
    @Value("\${application.notifications.expo-ids:}") expoIds: List<String>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cutoff =
        requireNotNull(publishFrom.takeIf { it.isNotBlank() }?.let(Instant::parse)) {
            "APPLICATION_NOTIFICATIONS_PUBLISH_FROM must be a fixed activation instant"
        }
    private val allowed = expoIds.filter { it.isNotBlank() }.map { UUID.fromString(it.trim()) }.toSet()

    @Scheduled(fixedDelayString = "\${application.notifications.delay-ms:5000}", scheduler = "applicationNotificationScheduler")
    fun publishDue() {
        repository.due(cutoff).filter { allowed.isEmpty() || it.expoId in allowed }.forEach { batch ->
            try {
                publish(batch)
            } catch (exception: Exception) {
                if (exception is InterruptedException || exception.cause is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                // 전화번호·이름·본문은 남기지 않는다.
                log.warn("Application notification deferred: {} {} ({})", batch.type, batch.batchKey, exception.javaClass.simpleName)
            }
        }
    }

    fun publish(batch: Batch) {
        jdbc.withDeliveryLock(49, "${batch.batchKey}:${batch.type}:${batch.expoId}") { scoped ->
            val rows = repository.pending(scoped, batch, cutoff)
            if (rows.isEmpty()) return@withDeliveryLock
            val resolved =
                try {
                    resolver.resolveBatch(rows)
                } catch (exception: Exception) {
                    repository.deferred(scoped, rows.map { it.id })
                    throw exception
                }
            resolved.filterIsInstance<ResolvedNotification.Skipped>().groupBy { it.reason }.forEach { (reason, skipped) ->
                repository.skipped(scoped, skipped.map { it.row.id }, reason)
            }
            var failure: Exception? = null
            resolved
                .filterIsInstance<ResolvedNotification.Ready>()
                .groupBy { listOf(it.phoneNumber, it.senderType, it.row.cancelledBy, it.scope) }
                .values
                .forEach { group ->
                    val ids = group.map { it.row.id }
                    try {
                        send(batch, group)
                        repository.delivered(scoped, ids)
                    } catch (exception: Exception) {
                        repository.deferred(scoped, ids)
                        if (exception is InterruptedException) throw exception
                        failure = failure ?: exception
                    }
                }
            failure?.let { throw it }
        }
    }

    private fun send(
        batch: Batch,
        group: List<ResolvedNotification.Ready>,
    ) {
        val first = group.first()
        val eventId = eventId(group)
        val event =
            ApplicationNotificationEvent(
                eventId = eventId,
                type = batch.type,
                phoneNumber = first.phoneNumber,
                expoId = batch.expoId,
                senderType = first.senderType,
                occurredAt = group.minOf { it.row.occurredAt }.toString(),
                items = group.map { it.item },
                cancelledBy = first.row.cancelledBy,
                scope = first.scope,
            )
        producer.send(ProducerRecord(topic, eventId.toString(), mapper.writeValueAsString(event))).get(35, TimeUnit.SECONDS)
    }

    companion object {
        // 같은 행 묶음은 항상 같은 eventId다. Notification이 이 값으로 중복 발송을 막는다.
        fun eventId(group: List<ResolvedNotification.Ready>): UUID =
            UUID.nameUUIDFromBytes(
                group
                    .map { it.row.dedupeKey }
                    .sorted()
                    .joinToString("|")
                    .toByteArray(),
            )
    }
}
