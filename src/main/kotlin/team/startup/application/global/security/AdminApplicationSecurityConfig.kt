package team.startup.application.global.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
class AdminApplicationSecurityConfig {
    @Bean
    @Order(0)
    fun adminApplicationSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher("/application/admin/**")
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            // Expo validates the original Bearer JWT and current authority before each controller reads data.
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
