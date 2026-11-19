package team.startup.application.domain.application.service.impl

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
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

    @Bean
    fun applicationNotificationProducer(
        @Value("\${application.notifications.bootstrap-servers:}") servers: String,
    ): KafkaTemplate<String, String> {
        require(servers.isNotBlank()) { "APPLICATION_NOTIFICATIONS_BOOTSTRAP_SERVERS must be configured" }
        return kafkaTemplate(servers)
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
    @Qualifier("applicationNotificationProducer") private val producer: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
    @Value("\${application.notifications.publish-from:}") publishFrom: String,
    @Value("\${application.notifications.topic:notification.sms.requested}") private val topic: String,
    @Value("\${application.notifications.expo-ids:}") expoIds: List<String>,
    private val gateway: RegistrationGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cutoff =
        requireNotNull(publishFrom.takeIf { it.isNotBlank() }?.let(Instant::parse)) {
            "APPLICATION_NOTIFICATIONS_PUBLISH_FROM must be a fixed activation instant"
        }
    private val allowed = expoIds.filter { it.isNotBlank() }.map { UUID.fromString(it.trim()) }.toSet()

    @Scheduled(fixedDelayString = "\${application.notifications.delay-ms:5000}", scheduler = "applicationNotificationScheduler")
    fun publishDue() {
        val enabled = mutableMapOf<UUID, Boolean?>()
        repository.due(cutoff).filter { allowed.isEmpty() || it.expoId in allowed }.forEach { batch ->
            try {
                // #418: 발행 직전에 잠금 밖에서 AUTO_NOTIFICATION을 본다(주기당 박람회별 1회). 확인 불가(null)는 행을 보류해 뒤로 미룬다.
                // getOrPut은 null을 저장하지 않으므로 확인 불가도 주기 안에서 다시 묻지 않게 직접 넣는다.
                if (batch.expoId !in enabled) {
                    enabled[batch.expoId] = gateway.featureStateOrNull(batch.expoId.toString(), ExpoFeature.AUTO_NOTIFICATION)
                }
                publish(batch, enabled[batch.expoId])
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

    // notify=false(자동 알림 OFF)면 보내지 않고 건너뜀으로 닫고, null(확인 불가)이면 보류한다. 신청·취소 상태 전이는 이미 커밋되어 그대로다.
    fun publish(
        batch: Batch,
        notify: Boolean? = true,
    ) {
        jdbc.withDeliveryLock(49, "${batch.batchKey}:${batch.type}:${batch.expoId}") { scoped ->
            val rows = repository.pending(scoped, batch, cutoff)
            if (rows.isEmpty()) return@withDeliveryLock
            if (notify == null) {
                repository.deferred(scoped, rows.map { it.id })
                ExpoFeature.unavailable()
            }
            if (!notify) {
                repository.skipped(scoped, rows.map { it.id }, ExpoFeature.DISABLED)
                return@withDeliveryLock
            }
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
        producer.send(topic, eventId.toString(), mapper.writeValueAsString(event)).get(35, TimeUnit.SECONDS)
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
