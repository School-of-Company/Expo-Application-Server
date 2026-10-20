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
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.PromotionOfferEvent
import team.startup.application.domain.application.repository.PromotionOffer
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.ReconcileProgramSessionService
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Configuration
@ConditionalOnProperty(name = ["application.promotion-offers.enabled"], havingValue = "true")
class PromotionOfferKafkaConfig {
    @Bean
    fun promotionOfferTaskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply { setThreadNamePrefix("promotion-offer-") }

    @Bean(destroyMethod = "close")
    fun promotionOfferProducer(
        @Value("\${application.promotion-offers.bootstrap-servers:}") servers: String,
    ): Producer<String, String> {
        require(servers.isNotBlank()) { "APPLICATION_PROMOTION_OFFERS_BOOTSTRAP_SERVERS must be configured" }
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

// Outbox publisher and sweeper for #144 offers. Kafka sends and remote promotion run outside database transactions;
// each local state change takes the same participant → schedule/group/program (or Expo) locks as other writers.
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["application.promotion-offers.enabled"], havingValue = "true")
class PromotionOfferScheduler(
    private val offers: PromotionOfferRepository,
    private val shared: PromotionOffers,
    private val policy: PromotionOfferPolicy,
    private val reconcile: ReconcileProgramSessionService,
    private val preregisterWaitlist: PreregisterWaitlist,
    private val ledger: PreregisterSessionLedger,
    @Qualifier("promotionOfferProducer") private val producer: Producer<String, String>,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${application.promotion-offers.delay-ms:5000}", scheduler = "promotionOfferTaskScheduler")
    fun run() {
        publishDue()
        sweep()
    }

    fun publishDue() {
        for (id in offers.due(clock.instant())) {
            try {
                val offer = offers.find(id) ?: continue
                val now = clock.instant()
                if (offer.state != "SCHEDULED" || !now.isBefore(offer.hardDeadline)) continue
                if (!policy.inWindow(now)) {
                    // Never handed to Kafka in this window; the next 09:00 is used only while still before the cutoff.
                    if (!offers.reschedule(id, policy.sendAfter(now))) offers.close(id, "VOIDED", null, voidReason = "NO_SEND_INTERVAL")
                    continue
                }
                offers.publish(id) { eventId ->
                    producer
                        .send(
                            ProducerRecord(
                                TOPIC,
                                eventId.toString(),
                                mapper.writeValueAsString(PromotionOfferEvent(1, eventId, offer.id, offer.expoId)),
                            ),
                        ).get(35, TimeUnit.SECONDS)
                }
            } catch (exception: Exception) {
                if (exception is InterruptedException || exception.cause is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                log.warn("Promotion offer publish deferred: {} ({})", id, exception.javaClass.simpleName)
            }
        }
    }

    fun sweep() {
        for (id in offers.sweepable(clock.instant())) {
            try {
                sweep(id)
            } catch (exception: RuntimeException) {
                log.warn("Promotion offer sweep deferred: {} ({})", id, exception.javaClass.simpleName)
            }
        }
    }

    // No-response expiry, including a definitive delivery failure past its deadline (#226), ends the wait and offers the
    // next party. Past the hard cutoff, or once the target left the queue, only the hold is released: unknown delivery
    // never becomes a decline.
    fun sweep(id: UUID) {
        val offer = offers.find(id) ?: return
        var next = false
        if (offer.target == "PROGRAM") {
            val definition = shared.snapshot(offer)
            ledger.transactions.executeWithoutResult {
                val current = shared.lockProgram(offer, definition)?.takeIf { it.active } ?: return@executeWithoutResult
                next =
                    close(current, shared.members(current).isNotEmpty()) {
                        shared.endProgramWait(current, "EXPIRED", ApplicationActorType.SYSTEM, null)
                    }
            }
            if (next) reconcile.execute(ProgramSessionKey(offer.expoId, definition.programType, definition.id))
        } else {
            ledger.transactions.executeWithoutResult {
                ledger.lock(offer.expoId)
                val current = offers.lock(id)?.takeIf { it.active } ?: return@executeWithoutResult
                val reservation = shared.reservation(current)?.takeIf { it.state == "WAITING" }
                next =
                    close(current, reservation != null) {
                        shared.endPreregisterWait(current, requireNotNull(reservation), "EXPIRED", ApplicationActorType.SYSTEM)
                    }
            }
            if (next) preregisterWaitlist.promoteQuietly(offer.expoId, requireNotNull(offer.sessionId))
        }
    }

    private fun close(
        offer: PromotionOffer,
        waiting: Boolean,
        expire: () -> Unit,
    ): Boolean {
        val action = sweepAction(offer, waiting, clock.instant())
        when (action) {
            SweepAction.VOID_TARGET_CLOSED -> offers.close(offer.id, "VOIDED", null, voidReason = "TARGET_CLOSED")
            SweepAction.EXPIRE -> expire()
            SweepAction.VOID_HARD_DEADLINE -> offers.close(offer.id, "VOIDED", null, voidReason = "HARD_DEADLINE")
            SweepAction.NONE -> Unit
        }
        return action == SweepAction.EXPIRE
    }

    private companion object {
        const val TOPIC = "application.promotion-offer.requested"
    }
}

enum class SweepAction { VOID_TARGET_CLOSED, EXPIRE, VOID_HARD_DEADLINE, NONE }

// expiresAt is capped at the hard deadline, so the two can coincide, and a late sweep can see an expiry that is already
// past the deadline (#227). Once now has reached the hard deadline the cutoff wins: only the hold is released and the
// waiting row is left alone, never read as a decline.
fun sweepAction(
    offer: PromotionOffer,
    waiting: Boolean,
    now: Instant,
): SweepAction {
    val expiresAt = offer.expiresAt
    val expirable = offer.state in listOf("SENT", "DELIVERY_FAILED") && expiresAt != null && expiresAt.isBefore(offer.hardDeadline)
    return when {
        !waiting -> SweepAction.VOID_TARGET_CLOSED
        !now.isBefore(offer.hardDeadline) -> SweepAction.VOID_HARD_DEADLINE
        expirable && !now.isBefore(expiresAt) -> SweepAction.EXPIRE
        else -> SweepAction.NONE
    }
}
