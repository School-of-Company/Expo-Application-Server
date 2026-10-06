package team.startup.application.global.security

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

@Configuration
class InternalSecurityConfig(
    private val internalTokenVerifier: InternalTokenVerifier,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val internal = PathPatternRequestMatcher.withDefaults().matcher("/internal/**")
        return http
            .csrf { it.ignoringRequestMatchers(internal) }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // 사용자 정의 체인이 Boot 기본 actuator 보안을 대체하므로 기본값과 같이 헬스 체크만 익명 허용한다.
                    .requestMatchers(EndpointRequest.to(HealthEndpoint::class.java))
                    .permitAll()
                    .requestMatchers(internal)
                    .hasAuthority(InternalTokenFilter.AUTHORITY)
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling { it.defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), internal) }
            .addFilterBefore(InternalTokenFilter(internalTokenVerifier), UsernamePasswordAuthenticationFilter::class.java)
            .httpBasic { }
            .formLogin { }
            .build()
    }
}

// 경로로 건너뛰지 않는다. 원본 URI와 디코딩된 경로가 달라 생기는 우회를 막기 위해 토큰이 맞을 때만 권한을 부여하고,
// /internal/** 허용 여부는 Spring Security 인가 규칙이 같은 경로 해석으로 판단한다.
class InternalTokenFilter(
    private val verifier: InternalTokenVerifier,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (verifier.matches(request.getHeader(HEADER))) {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated("internal-service", null, listOf(SimpleGrantedAuthority(AUTHORITY)))
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Internal-Token"
        const val AUTHORITY = "INTERNAL_SERVICE"
    }
}

@Component
class InternalTokenVerifier(
    @Value("\${application.internal-token:}") private val expected: String,
) {
    fun matches(actual: String?): Boolean =
        expected.isNotBlank() &&
            actual != null &&
            MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}
