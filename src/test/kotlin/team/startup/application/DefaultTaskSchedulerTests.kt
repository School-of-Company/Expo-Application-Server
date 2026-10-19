package team.startup.application

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import team.startup.application.global.scheduling.DefaultTaskSchedulerConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class DefaultTaskSchedulerTests {
    @Test
    fun `long default task does not delay other default tasks`() {
        val blocker = Blocker()
        val heartbeat = Heartbeat()
        AnnotationConfigApplicationContext().use { context ->
            context.register(SchedulingOn::class.java, DefaultTaskSchedulerConfig::class.java)
            context.registerBean("blocker", Blocker::class.java, Supplier { blocker })
            context.registerBean("heartbeat", Heartbeat::class.java, Supplier { heartbeat })
            context.refresh()
            try {
                assertTrue(blocker.started.await(5, TimeUnit.SECONDS))
                heartbeat.pulses = CountDownLatch(3)
                assertTrue(heartbeat.pulses.await(2, TimeUnit.SECONDS))
            } finally {
                blocker.release.countDown()
            }
        }
    }

    @Configuration
    @EnableScheduling
    class SchedulingOn

    class Blocker {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Scheduled(fixedDelay = 20)
        fun block() {
            started.countDown()
            release.await()
        }
    }

    class Heartbeat {
        @Volatile var pulses = CountDownLatch(3)

        @Scheduled(fixedDelay = 20)
        fun tick() {
            pulses.countDown()
        }
    }
}
