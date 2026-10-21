package team.startup.application.domain.application.service.impl

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
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.ProgramPromotionRepository
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

@Configuration
@ConditionalOnProperty(name = ["application.promotions.enabled"], havingValue = "true")
class ProgramPromotionKafkaConfig {
    @Bean
    fun programPromotionScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply { setThreadNamePrefix("program-promotion-") }

    @Bean(destroyMethod = "close")
    fun promotionProducer(
        @Value("\${application.promotions.bootstrap-servers:}") servers: String,
    ): Producer<String, String> {
        require(servers.isNotBlank()) { "APPLICATION_PROMOTIONS_BOOTSTRAP_SERVERS must be configured" }
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

@Component
@ConditionalOnProperty(name = ["application.promotions.enabled"], havingValue = "true")
class ProgramPromotionPublisher(
    private val repository: ProgramPromotionRepository,
    @Qualifier("promotionProducer") private val producer: Producer<String, String>,
    private val mapper: ObjectMapper,
    private val policy: ProgramPromotionPolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${application.promotions.delay-ms:5000}", scheduler = "programPromotionScheduler")
    fun publishDue() {
        val cutoff = policy.cutoff()
        repository.due(cutoff).forEach { eventId ->
            try {
                repository.publish(eventId, cutoff) { event ->
                    require(event.participantId in 1..9007199254740991 && event.programId in 1..9007199254740991)
                    producer
                        .send(
                            ProducerRecord("application.program-session.promoted", eventId.toString(), mapper.writeValueAsString(event)),
                        ).get(35, TimeUnit.SECONDS)
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
