package team.startup.application.domain.application.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ProgramSessionClockConfig {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun programSessionClock(): Clock = Clock.systemUTC()
}
