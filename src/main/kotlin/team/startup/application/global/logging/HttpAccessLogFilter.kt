package team.startup.application.global.logging

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.util.DisconnectedClientHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 요청마다 접근 로그 한 줄(`GET /application/expos/{expoId}/field-visits status: 200 (12ms)`)을 남긴다. Expo 전 서비스가 같은 형식을 쓴다.
 * 보안 필터 체인보다 바깥에서 돌아 401/403 거절도 남긴다. 쿼리 문자열·헤더·쿠키·본문은 남기지 않는다.
 * 비동기(SSE) 요청은 비동기 처리가 끝날 때 한 번 남긴다. 클라이언트가 끊으면 499를 ERROR로 한 번만 남긴다.
 */
class HttpAccessLogFilter(
    private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME),
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return SKIPPED_PREFIXES.any { path == it || path.startsWith("$it/") } || path in SKIPPED_PATHS
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val access = Access(request, response, System.nanoTime())
        try {
            filterChain.doFilter(request, response)
        } catch (ex: Throwable) {
            access.failed(ex)
            throw ex
        }
        if (request.isAsyncStarted) {
            request.asyncContext.addListener(access)
        } else {
            access.write(response.status)
        }
    }

    private inner class Access(
        private val request: HttpServletRequest,
        private val response: HttpServletResponse,
        private val startedAt: Long,
    ) : AsyncListener {
        private val logged = AtomicBoolean(false)

        fun failed(ex: Throwable) = write(if (DisconnectedClientHelper.isClientDisconnectedException(ex)) CLIENT_CLOSED else serverError())

        fun write(status: Int) {
            if (!logged.compareAndSet(false, true)) return
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val method = request.method
            val path = pathOf(request)
            when {
                status == CLIENT_CLOSED || status >= 500 -> log.error("{} {} status: {} ({}ms)", method, path, status, elapsedMs)
                status >= 400 -> log.warn("{} {} status: {} ({}ms)", method, path, status, elapsedMs)
                else -> log.info("{} {} status: {} ({}ms)", method, path, status, elapsedMs)
            }
        }

        private fun serverError() = if (response.status >= 500) response.status else 500

        override fun onComplete(event: AsyncEvent) = write(response.status)

        override fun onError(event: AsyncEvent) {
            event.throwable?.let { failed(it) } ?: write(serverError())
        }

        // 시간 초과는 onComplete로 이어지고 그때의 상태로 남긴다.
        override fun onTimeout(event: AsyncEvent) = Unit

        override fun onStartAsync(event: AsyncEvent) = event.asyncContext.addListener(this)
    }

    companion object {
        // ModuleNameConverter가 global 다음 이름을 module로 쓰므로 module은 HttpAccess가 된다.
        const val LOGGER_NAME = "team.startup.application.global.HttpAccess"
        const val CLIENT_CLOSED = 499
        private const val MAX_PATH_LENGTH = 200
        private val SKIPPED_PREFIXES =
            listOf("/actuator", "/application/v3/api-docs", "/application/swagger-ui", "/v3/api-docs", "/swagger-ui")
        private val SKIPPED_PATHS = setOf("/application/swagger-ui.html", "/swagger-ui.html")
        private val PATH_PARAMETERS = Regex(";[^/]*")

        fun pathOf(request: HttpServletRequest): String =
            (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
                ?: request.requestURI.replace(PATH_PARAMETERS, "").take(MAX_PATH_LENGTH)
    }
}

@Configuration
@ConditionalOnProperty(name = ["logging.http-access.enabled"], havingValue = "true", matchIfMissing = true)
class HttpAccessLogConfig {
    // 관측 필터(HIGHEST_PRECEDENCE + 1) 안쪽이라 traceId MDC가 있으면 붙고, 보안 필터(-100)보다 바깥이라 거절도 본다.
    @Bean
    fun httpAccessLogFilter(): FilterRegistrationBean<HttpAccessLogFilter> =
        FilterRegistrationBean(HttpAccessLogFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE + 2 }
}
