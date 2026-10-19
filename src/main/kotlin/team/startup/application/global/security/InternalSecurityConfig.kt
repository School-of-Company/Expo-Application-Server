package team.startup.application.global.security

import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Configuration
class InternalSecurityConfig(
    private val internalTokenVerifier: InternalTokenVerifier,
    @Value("\${application.registration.enabled:false}") private val registrationEnabled: Boolean,
    @Value("\${swagger.enabled:false}") private val swaggerEnabled: Boolean,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val internal = PathPatternRequestMatcher.withDefaults().matcher("/internal/**")
        val adminGroupMessages = PathPatternRequestMatcher.withDefaults().matcher("/application/expos/{expoId}/admin-group-messages/**")
        // An anonymous counter has no session or credential for CSRF to protect.
        val pageViews = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/application/expos/{expoId}/page-views")
        val registration =
            listOf(
                "/application/{expoId}",
                "/application/pre-standard/{expoId}",
                "/application/field/{expoId}",
                "/application/field/standard/{expoId}",
            ).map { PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, it) }
        return http
            .csrf { csrf ->
                // Double-submit cookie keeps owner cancellation valid across instances and restarts.
                // Keep the X-CSRF-TOKEN header contract instead of the cookie repository's X-XSRF-TOKEN default.
                csrf.csrfTokenRepository(CookieCsrfTokenRepository().apply { setHeaderName("X-CSRF-TOKEN") })
                csrf.ignoringRequestMatchers(internal)
                csrf.ignoringRequestMatchers(adminGroupMessages)
                csrf.ignoringRequestMatchers(pageViews)
                if (registrationEnabled) csrf.ignoringRequestMatchers(*registration.toTypedArray())
            }.authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("health"))
                    .permitAll()
                    .requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, "health"))
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("prometheus").withHttpMethod(HttpMethod.GET))
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/application/expos/{expoId}/my-applications")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/application/expos/{expoId}/my-applications/{type}/{applicationId}/cancellation")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/application/expos/{expoId}/my-applications/preregister-waitlist")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/application/expos/{expoId}/my-applications/preregister-waitlists")
                    .permitAll()
                    // #200: read-only preview; the participant session cookie authenticates.
                    .requestMatchers(
                        HttpMethod.GET,
                        "/application/expos/{expoId}/my-applications/PREREGISTER/{applicationId}/cancellation-preview",
                    ).permitAll()
                    // #169: the participant session cookie authenticates and the POST stays CSRF protected.
                    .requestMatchers(HttpMethod.POST, "/application/expos/{expoId}/my-applications/PREREGISTER/{applicationId}/companions")
                    .permitAll()
                    // #199: same session cookie and CSRF protection as #169.
                    .requestMatchers(HttpMethod.POST, "/application/expos/{expoId}/my-applications/preregister-sessions")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/application/expos/{expoId}/my-applications/preregister-waitlist/cancellation")
                    .permitAll()
                    // #144 offers: the participant session cookie authenticates, and the POSTs stay CSRF protected.
                    .requestMatchers(HttpMethod.GET, "/application/expos/{expoId}/my-applications/promotion-offers")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/application/expos/{expoId}/my-applications/promotion-offers/{offerId}/acceptance")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/application/expos/{expoId}/my-applications/promotion-offers/{offerId}/decline")
                    .permitAll()
                    .requestMatchers(adminGroupMessages)
                    .permitAll()
                    .requestMatchers(pageViews)
                    .permitAll()
                if (swaggerEnabled) {
                    requests
                        .requestMatchers(
                            HttpMethod.GET,
                            "/application/v3/api-docs",
                            "/application/v3/api-docs/**",
                            "/application/swagger-ui.html",
                            "/application/swagger-ui/**",
                        ).permitAll()
                        .requestMatchers(
                            "/application/v3/api-docs",
                            "/application/v3/api-docs/**",
                            "/application/swagger-ui.html",
                            "/application/swagger-ui/**",
                        ).denyAll()
                }
                requests.requestMatchers(internal).access {
                    _,
                    context,
                    ->
                    AuthorizationDecision(internalTokenVerifier.matches(context.request.getHeader("X-Internal-Token")))
                }
                if (registrationEnabled) requests.requestMatchers(*registration.toTypedArray()).permitAll()
                requests
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling { it.defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), internal) }
            .cors(withDefaults())
            .httpBasic { }
            .formLogin { }
            .build()
    }
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
