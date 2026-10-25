package team.startup.application.domain.application.service.impl

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 회로가 열려 하위 서비스를 부르지 않고 실패했다. 각 게이트웨이가 연결 실패(503)로 이미 바꾸는 [IOException]이다. */
class DownstreamCircuitOpenException(
    val service: String,
) : IOException("downstream circuit open: $service")

/** 회로 차단기를 나누는 하위 서비스. 한 서비스가 죽어도 다른 서비스 호출은 막히지 않는다. */
object Downstream {
    const val EXPO = "expo"
    const val USER = "user"
    const val FORM = "form"
    const val ATTENDANCE = "attendance"
    const val NOTIFICATION = "notification"
}

/**
 * 하위 서비스 HTTP 호출의 회로 차단기와 제한적 재시도.
 *
 * 서비스별로 차단기 하나를 둔다. 장애로 세는 것은 타임아웃·연결 실패·5xx뿐이며, 404·409·401 같은 4xx와 본문 해석 오류는
 * 서비스가 정상적으로 답한 것이므로 세지 않는다. 재시도는 멱등한 GET이 연결 실패처럼 즉시 실패했을 때만 75ms 뒤에 한 번 한다. 요청 한 건은 회로에 한 건으로 세며 재시도 앞의 실패는 세지 않는다.
 * 타임아웃과 쓰기(PUT/POST/DELETE)는 이미 처리됐을 수 있어 다시 보내지 않는다. 회로가 열리면 [DownstreamCircuitOpenException]을
 * 던지므로 호출부는 기존의 연결 실패 처리로 같은 오류를 돌려준다.
 */
@Component
class DownstreamCircuits {
    private val registry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig
                .custom()
                .slidingWindowSize(WINDOW_SIZE)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(Duration.ofSeconds(OPEN_SECONDS))
                .build(),
        )

    fun breaker(service: String): CircuitBreaker = registry.circuitBreaker(service)

    fun <T> send(
        service: String,
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val idempotent = request.method() == "GET"
        return try {
            attempt(service, client, request, handler, willRetry = idempotent)
        } catch (e: IOException) {
            if (!idempotent || !retryable(e)) throw e
            pause()
            attempt(service, client, request, handler, willRetry = false)
        }
    }

    private fun pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * 사용자 요청 한 건은 회로에 한 건으로 센다. 재시도가 뒤따를 첫 시도의 연결 실패는 표본에서 빼고 마지막 시도만 센다.
     */
    private fun <T> attempt(
        service: String,
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        willRetry: Boolean,
    ): HttpResponse<T> {
        val breaker = breaker(service)
        if (!breaker.tryAcquirePermission()) throw DownstreamCircuitOpenException(service)
        val started = System.nanoTime()
        val response =
            try {
                client.send(request, handler)
            } catch (e: IOException) {
                if (willRetry && retryable(e)) {
                    breaker.releasePermission()
                } else {
                    breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, e)
                }
                throw e
            } catch (e: Throwable) {
                // 중단이나 요청 구성 오류는 하위 서비스의 상태가 아니므로 표본에서 뺀다
                breaker.releasePermission()
                throw e
            }
        val elapsed = System.nanoTime() - started
        if (response.statusCode() in 500..599) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, IOException("downstream ${response.statusCode()}"))
        } else {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS)
        }
        return response
    }

    private fun retryable(e: IOException) = e !is HttpTimeoutException && e !is DownstreamCircuitOpenException

    private companion object {
        const val WINDOW_SIZE = 20
        const val MIN_CALLS = 10
        const val FAILURE_RATE_THRESHOLD = 50f
        const val OPEN_SECONDS = 10L
        const val RETRY_DELAY_MS = 75L
    }
}
