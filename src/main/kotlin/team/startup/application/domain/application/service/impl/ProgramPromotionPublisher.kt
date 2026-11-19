package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.ProgramPromotionRepository
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.TimeUnit

@Configuration
@ConditionalOnProperty(name = ["application.promotions.enabled"], havingValue = "true")
class ProgramPromotionKafkaConfig {
    @Bean
    fun programPromotionScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply { setThreadNamePrefix("program-promotion-") }

    @Bean
    fun promotionProducer(
        @Value("\${application.promotions.bootstrap-servers:}") servers: String,
    ): KafkaTemplate<String, String> {
        require(servers.isNotBlank()) { "APPLICATION_PROMOTIONS_BOOTSTRAP_SERVERS must be configured" }
        return kafkaTemplate(servers)
    }
}

@Component
@ConditionalOnProperty(name = ["application.promotions.enabled"], havingValue = "true")
class ProgramPromotionPublisher(
    private val repository: ProgramPromotionRepository,
    @Qualifier("promotionProducer") private val producer: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
    private val policy: ProgramPromotionPolicy,
    private val gateway: RegistrationGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${application.promotions.delay-ms:5000}", scheduler = "programPromotionScheduler")
    fun publishDue() {
        val cutoff = policy.cutoff()
        val enabled = mutableMapOf<UUID, Boolean?>()
        repository.due(cutoff).forEach { eventId ->
            try {
                // #418: 승급은 이미 확정됐다. 자동 알림 OFF면 안내만 생략하고 전달 완료로 닫으며, 확인 불가(null)면 재시도 간격을 늘려 보류한다.
                // 정책은 전달 잠금 밖에서 주기당 박람회별 1회 본다.
                // 승급 행이 없으면(이미 바뀐 이력) publish가 아무것도 하지 않는다. 그때만 정책을 보지 않는다.
                val expoId = repository.expoId(eventId)
                val notify =
                    if (expoId == null) {
                        true
                    } else {
                        // getOrPut은 null을 저장하지 않으므로 확인 불가도 주기 안에서 다시 묻지 않게 직접 넣는다.
                        if (expoId !in
                            enabled
                        ) {
                            enabled[expoId] = gateway.featureStateOrNull(expoId.toString(), ExpoFeature.AUTO_NOTIFICATION)
                        }
                        enabled[expoId]
                    }
                repository.publish(eventId, cutoff, notify) { event ->
                    require(event.participantId in 1..9007199254740991 && event.programId in 1..9007199254740991)
                    producer
                        .send("application.program-session.promoted", eventId.toString(), mapper.writeValueAsString(event))
                        .get(35, TimeUnit.SECONDS)
                }
            } catch (exception: Exception) {
                if (exception is InterruptedException || exception.cause is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                log.warn("Program promotion publish deferred: {} ({})", eventId, exception.javaClass.simpleName)
            }
        }
    }
}
