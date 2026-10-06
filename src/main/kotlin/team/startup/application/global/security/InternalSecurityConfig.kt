package team.startup.application.global.security

import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Configuration
class InternalSecurityConfig(
    private val internalTokenVerifier: InternalTokenVerifier,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.ignoringRequestMatchers("/internal/**") }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/internal/**")
                    .access {
                        _,
                        context,
                        ->
                        AuthorizationDecision(internalTokenVerifier.matches(context.request.getHeader("X-Internal-Token")))
                    }.anyRequest()
                    .authenticated()
            }.httpBasic { }
            .formLogin { }
            .build()
}

@Component
class InternalTokenVerifier(
    @Value("\${application.internal-token:}") private val expected: String,
) {
    init {
        require(expected.isNotBlank()) { "APPLICATION_INTERNAL_TOKEN must be configured" }
    }

    fun matches(actual: String?) =
        actual != null &&
            MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}
