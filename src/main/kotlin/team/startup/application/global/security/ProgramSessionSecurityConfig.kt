package team.startup.application.global.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher

@Configuration
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class ProgramSessionSecurityConfig {
    @Bean
    @Order(1)
    fun programSessionSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher(
                OrRequestMatcher(
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/application/program-session-csrf"),
                    PathPatternRequestMatcher.withDefaults().matcher(
                        HttpMethod.POST,
                        "/application/expos/{expoId}/program-sessions/{programType}/{programId}/applications",
                    ),
                ),
            ).csrf { it.csrfTokenRepository(CookieCsrfTokenRepository()) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .exceptionHandling {
                it.accessDeniedHandler { _, response, _ ->
                    response.status = 403
                    response.contentType = "application/json"
                    response.writer.write("""{"status":403,"code":"CSRF_REQUIRED"}""")
                }
            }.build()
}
