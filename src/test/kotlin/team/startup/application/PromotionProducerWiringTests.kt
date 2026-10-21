package team.startup.application

import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import team.startup.application.domain.application.repository.ProgramPromotionRepository
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.ReconcileProgramSessionService
import team.startup.application.domain.application.service.impl.PreregisterSessionLedger
import team.startup.application.domain.application.service.impl.PreregisterWaitlist
import team.startup.application.domain.application.service.impl.ProgramPromotionPolicy
import team.startup.application.domain.application.service.impl.ProgramPromotionPublisher
import team.startup.application.domain.application.service.impl.PromotionOfferPolicy
import team.startup.application.domain.application.service.impl.PromotionOfferScheduler
import team.startup.application.domain.application.service.impl.PromotionOffers
import tools.jackson.databind.ObjectMapper
import java.time.Clock

// #272: 두 기능을 같이 켜면 Producer 빈이 둘이 된다. 실제 Kafka 대신 설정 클래스와 같은 이름의 MockProducer를 등록한다.
class PromotionProducerWiringTests {
    private val promotion = MockProducer<String, String>(true, null, StringSerializer(), StringSerializer())
    private val offer = MockProducer<String, String>(true, null, StringSerializer(), StringSerializer())

    private fun runner(
        promotions: Boolean,
        offers: Boolean,
    ): ApplicationContextRunner {
        var runner =
            ApplicationContextRunner()
                .withPropertyValues("application.promotions.enabled=$promotions", "application.promotion-offers.enabled=$offers")
                .withBean(ObjectMapper::class.java, { ObjectMapper() })
                .withBean(Clock::class.java, { Clock.systemUTC() })
                .withUserConfiguration(ProgramPromotionPublisher::class.java, PromotionOfferScheduler::class.java)
        if (promotions) {
            runner =
                runner
                    .withBean("promotionProducer", Producer::class.java, { promotion })
                    .withBean("programPromotionScheduler", ThreadPoolTaskScheduler::class.java, { ThreadPoolTaskScheduler() })
                    .withBean(ProgramPromotionRepository::class.java, { mock(ProgramPromotionRepository::class.java) })
                    .withBean(ProgramPromotionPolicy::class.java, { mock(ProgramPromotionPolicy::class.java) })
        }
        if (offers) {
            runner =
                runner
                    .withBean("promotionOfferProducer", Producer::class.java, { offer })
                    .withBean("promotionOfferTaskScheduler", ThreadPoolTaskScheduler::class.java, { ThreadPoolTaskScheduler() })
                    .withBean(PromotionOfferRepository::class.java, { mock(PromotionOfferRepository::class.java) })
                    .withBean(PromotionOffers::class.java, { mock(PromotionOffers::class.java) })
                    .withBean(PromotionOfferPolicy::class.java, { mock(PromotionOfferPolicy::class.java) })
                    .withBean(ReconcileProgramSessionService::class.java, { mock(ReconcileProgramSessionService::class.java) })
                    .withBean(PreregisterWaitlist::class.java, { mock(PreregisterWaitlist::class.java) })
                    .withBean(PreregisterSessionLedger::class.java, { mock(PreregisterSessionLedger::class.java) })
        }
        return runner
    }

    private fun producerOf(
        context: ApplicationContext,
        type: Class<*>,
    ): Any? = type.getDeclaredField("producer").apply { isAccessible = true }.get(context.getBean(type))

    @Test
    fun `both features start and each publisher uses its own producer`() {
        runner(promotions = true, offers = true).run { context ->
            assertNull(context.startupFailure)
            assertSame(promotion, producerOf(context, ProgramPromotionPublisher::class.java))
            assertSame(offer, producerOf(context, PromotionOfferScheduler::class.java))
        }
    }

    @Test
    fun `each feature starts alone with its own producer`() {
        runner(promotions = true, offers = false).run { context ->
            assertNull(context.startupFailure)
            assertSame(promotion, producerOf(context, ProgramPromotionPublisher::class.java))
            assertEquals(0, context.getBeanNamesForType(PromotionOfferScheduler::class.java).size)
        }
        runner(promotions = false, offers = true).run { context ->
            assertNull(context.startupFailure)
            assertSame(offer, producerOf(context, PromotionOfferScheduler::class.java))
            assertEquals(0, context.getBeanNamesForType(ProgramPromotionPublisher::class.java).size)
        }
    }

    @Test
    fun `both features off create no publisher`() {
        runner(promotions = false, offers = false).run { context ->
            assertNull(context.startupFailure)
            assertEquals(0, context.getBeanNamesForType(ProgramPromotionPublisher::class.java).size)
            assertEquals(0, context.getBeanNamesForType(PromotionOfferScheduler::class.java).size)
            assertEquals(0, context.getBeanNamesForType(Producer::class.java).size)
        }
    }
}
