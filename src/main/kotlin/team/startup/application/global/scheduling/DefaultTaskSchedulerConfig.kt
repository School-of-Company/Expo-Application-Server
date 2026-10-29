package team.startup.application.global.scheduling

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

// Shared pool for every @Scheduled task without its own scheduler. Declared unconditionally so Boot's single-thread
// default never applies and named pools (promotion, offers) do not take over the default. One thread per default task
// (preregister recovery and waitlist promotion, session attendance, program waitlist, owner cancellation, page-view
// purge, and field visit recovery) keeps a slow remote call from delaying the 1-second recovery and attendance loops.
@Configuration
class DefaultTaskSchedulerConfig {
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 7
            setThreadNamePrefix("application-scheduler-")
        }
}
