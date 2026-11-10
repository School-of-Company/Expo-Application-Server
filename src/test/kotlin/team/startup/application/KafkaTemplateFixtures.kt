package team.startup.application

import org.apache.kafka.clients.producer.Producer
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import java.time.Duration

// KafkaTemplate은 보낼 때마다 producer를 닫는다. 실제 팩토리처럼 닫기를 무시해 테스트 producer를 계속 쓴다.
fun kafkaTemplateOf(producer: Producer<String, String>): KafkaTemplate<String, String> =
    KafkaTemplate(
        ProducerFactory {
            object : Producer<String, String> by producer {
                override fun close(timeout: Duration) = Unit
            }
        },
    )
