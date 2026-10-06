package team.startup.application.global.security

import org.springframework.boot.actuate.endpoint.web.WebServerNamespace
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

// Boot 기본 actuator 보안(ManagementWebSecurityAutoConfiguration)과 같고, prometheus GET만 추가로 연다.
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(EndpointRequest.to("health"))
                    .permitAll()
                    .requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, "health"))
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("prometheus").withHttpMethod(HttpMethod.GET))
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.cors(withDefaults())
            .formLogin { }
            .httpBasic { }
            .build()
}
