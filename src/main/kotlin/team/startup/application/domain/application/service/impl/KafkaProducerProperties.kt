package team.startup.application.domain.application.service.impl

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer

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
