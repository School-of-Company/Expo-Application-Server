package team.startup.application

import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.impl.Downstream
import team.startup.application.domain.application.service.impl.DownstreamCircuits
import team.startup.application.domain.application.service.impl.PreregisterSessionGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 하위 서비스별 회로 차단기와 제한적 재시도가 정해진 규칙대로 동작하는지 확인한다. */
class DownstreamCircuitsTests {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger()

    @Volatile private var code = 500
    private val circuits = DownstreamCircuits()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun uri() = URI.create("http://127.0.0.1:${server.address.port}/x")

    private fun get() = HttpRequest.newBuilder(uri()).GET().build()

    private fun post() = HttpRequest.newBuilder(uri()).POST(HttpRequest.BodyPublishers.ofString("{}")).build()

    private fun call(
        service: String,
        request: HttpRequest,
    ) = circuits.send(service, client, request, HttpResponse.BodyHandlers.discarding())

    @Test
    fun `5xx가 계속되면 회로가 열려 하위 서비스를 부르지 않고 바로 실패한다`() {
        repeat(10) { assertEquals(500, call(Downstream.USER, get()).statusCode()) }
        assertEquals(CircuitBreaker.State.OPEN, circuits.breaker(Downstream.USER).state)

        val before = hits.get()
        assertThrows(java.io.IOException::class.java) { call(Downstream.USER, get()) }
        assertEquals(before, hits.get())
    }

    @Test
    fun `한 서비스의 회로가 열려도 다른 서비스 호출은 막히지 않는다`() {
        repeat(10) { call(Downstream.USER, get()) }
        assertEquals(CircuitBreaker.State.OPEN, circuits.breaker(Downstream.USER).state)

        code = 200
        assertEquals(200, call(Downstream.EXPO, get()).statusCode())
        assertEquals(CircuitBreaker.State.CLOSED, circuits.breaker(Downstream.EXPO).state)
    }

    @Test
    fun `404 409 401 같은 4xx는 아무리 반복해도 회로를 열지 않는다`() {
        for (status in listOf(404, 409, 401, 400, 422)) {
            code = status
            repeat(30) { assertEquals(status, call(Downstream.EXPO, get()).statusCode()) }
        }
        assertEquals(CircuitBreaker.State.CLOSED, circuits.breaker(Downstream.EXPO).state)
        assertEquals(0, circuits.breaker(Downstream.EXPO).metrics.numberOfFailedCalls)
    }

    @Test
    fun `5xx 응답은 재시도하지 않고 쓰기 요청도 연결 실패를 재시도하지 않는다`() {
        call(Downstream.EXPO, get())
        assertEquals(1, hits.get())

        val dead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/x"))
        assertThrows(
            java.io.IOException::class.java,
        ) { call(Downstream.ATTENDANCE, dead.POST(HttpRequest.BodyPublishers.ofString("{}")).build()) }
        assertEquals(1, circuits.breaker(Downstream.ATTENDANCE).metrics.numberOfFailedCalls)
        assertThrows(java.io.IOException::class.java) { call(Downstream.ATTENDANCE, dead.DELETE().build()) }
        assertEquals(2, circuits.breaker(Downstream.ATTENDANCE).metrics.numberOfFailedCalls)
    }

    @Test
    fun `GET은 연결 실패에 한해 한 번만 다시 시도한다`() {
        val dead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/x")).GET().build()
        assertThrows(java.io.IOException::class.java) { call(Downstream.FORM, dead) }
        assertEquals(2, circuits.breaker(Downstream.FORM).metrics.numberOfFailedCalls)
    }

    @Test
    fun `타임아웃은 장애로 세지만 다시 시도하지 않는다`() {
        server.removeContext("/")
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            Thread.sleep(600)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        val slow =
            HttpRequest
                .newBuilder(uri())
                .timeout(Duration.ofMillis(100))
                .GET()
                .build()
        assertThrows(java.io.IOException::class.java) { call(Downstream.NOTIFICATION, slow) }
        assertEquals(1, circuits.breaker(Downstream.NOTIFICATION).metrics.numberOfFailedCalls)
        Thread.sleep(700)
        assertEquals(1, hits.get())
    }

    @Test
    fun `게이트웨이는 회로가 열리면 기존 장애와 같은 503으로 실패하고 정상 거절은 그대로 돌려준다`() {
        val gateway = PreregisterSessionGateway(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}", "t", circuits)
        val expo = UUID.randomUUID()

        code = 404
        repeat(30) { assertEquals(null, gateway.find(expo, 7)) }
        code = 409
        repeat(
            30,
        ) { assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException::class.java) { gateway.find(expo, 7) }.statusCode) }
        assertEquals(CircuitBreaker.State.CLOSED, circuits.breaker(Downstream.EXPO).state)

        code = 500
        repeat(10) {
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException::class.java) { gateway.find(expo, 7) }.statusCode,
            )
        }
        assertEquals(CircuitBreaker.State.OPEN, circuits.breaker(Downstream.EXPO).state)

        val before = hits.get()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException::class.java) { gateway.find(expo, 7) }.statusCode)
        assertEquals(before, hits.get())
    }
}
