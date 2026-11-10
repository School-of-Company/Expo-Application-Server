package team.startup.application.domain.application.service.impl

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

fun kafkaProducerProperties(bootstrapServers: String): Map<String, Any> =
    mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ProducerConfig.MAX_BLOCK_MS_CONFIG to 5000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 10000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 30000,
    )

// outbox 발행용. 실패는 각 발행기가 본문 없이 남기므로, payload(전화번호 포함)를 ERROR로 찍는 기본 리스너는 끈다.
fun kafkaTemplate(bootstrapServers: String): KafkaTemplate<String, String> =
    KafkaTemplate(DefaultKafkaProducerFactory<String, String>(kafkaProducerProperties(bootstrapServers)))
        .apply { setProducerListener(null) }
