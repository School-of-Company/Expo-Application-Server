package team.startup.application

import com.sun.net.httpserver.HttpServer
import feign.Client
import feign.Logger
import feign.Request
import feign.RequestInterceptor
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.cloud.openfeign.FeignAutoConfiguration
import org.springframework.cloud.openfeign.FeignClientFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.impl.AdminAttendanceGateway
import team.startup.application.domain.application.service.impl.AttendanceCancellationStatusGateway
import team.startup.application.domain.application.service.impl.Downstream
import team.startup.application.domain.application.service.impl.DownstreamCircuits
import team.startup.application.global.client.attendance.AttendanceClient
import team.startup.application.global.client.attendance.AttendanceClientProperties
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AttendanceFeignTests {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    private val release = CountDownLatch(1)

    @Volatile private var code = 200

    @Volatile private var body = "[]"

    @Volatile private var hold = false

    @Volatile private var path = ""
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration::class.java))
            .withUserConfiguration(Pilot::class.java)
            .withPropertyValues("application.internal-token=application-self-token")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            path = exchange.requestURI.path
            assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            exchange.requestBody.readAllBytes()
            if (hold) release.await(15, TimeUnit.SECONDS)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun configured() =
        runner.withPropertyValues(
            "application.attendance.url=http://127.0.0.1:${server.address.port}///",
            "application.attendance.internal-token=attendance-token",
        )

    @Test
    fun `Feign 4xx와 본문 해석 오류는 회로 장애로 세지 않으며 토큰 설정은 전역에 새지 않는다`() {
        configured().run { context ->
            assertNull(context.startupFailure)
            val gateway = context.getBean(AdminAttendanceGateway::class.java)
            val circuits = context.getBean(DownstreamCircuits::class.java)
            for (status in listOf(400, 401, 403, 404, 409, 422)) {
                code = status
                failure(HttpStatus.BAD_GATEWAY) { gateway.attended(ProgramSessionType.STANDARD, 7, 2) }
            }
            code = 200
            body = "{"
            failure(HttpStatus.BAD_GATEWAY) { gateway.attended(ProgramSessionType.STANDARD, 7, 2) }
            body = "[]"
            assertFalse(gateway.attended(ProgramSessionType.STANDARD, 7, 2))
            assertEquals("/internal/program-attendances/standard/7", path)
            assertEquals(0, circuits.breaker(Downstream.ATTENDANCE).metrics.numberOfFailedCalls)
            assertEquals(8, circuits.breaker(Downstream.ATTENDANCE).metrics.numberOfBufferedCalls)
            assertEquals(8, hits.get())
            assertEquals(emptyMap<String, Client>(), context.getBeansOfType(Client::class.java))
            assertEquals(emptyMap<String, RequestInterceptor>(), context.getBeansOfType(RequestInterceptor::class.java))
            val factory = context.getBean(FeignClientFactory::class.java)
            assertEquals(1, requireNotNull(factory.getInstancesWithoutAncestors("attendanceRead", RequestInterceptor::class.java)).size)
            val options = requireNotNull(factory.getInstance("attendanceRead", Request.Options::class.java))
            assertEquals(3000, options.connectTimeoutMillis())
            assertEquals(8000, options.readTimeoutMillis())
            assertEquals(Logger.Level.NONE, factory.getInstance("attendanceRead", Logger.Level::class.java))
            assertFalse(context.getBean(AttendanceClientProperties::class.java).toString().contains("attendance-token"))
        }
    }

    @Test
    fun `Feign 5xx는 재시도 없이 표본 하나이며 두 게이트웨이는 같은 Attendance 회로를 쓴다`() {
        configured().run { context ->
            val admin = context.getBean(AdminAttendanceGateway::class.java)
            val cancellation = context.getBean(AttendanceCancellationStatusGateway::class.java)
            code = 503
            repeat(5) {
                failure(HttpStatus.SERVICE_UNAVAILABLE) { admin.attended(ProgramSessionType.STANDARD, 7, 2) }
                failure(HttpStatus.SERVICE_UNAVAILABLE) { cancellation.find(UUID.randomUUID(), 2, "01012345678") }
            }
            val breaker = context.getBean(DownstreamCircuits::class.java).breaker(Downstream.ATTENDANCE)
            assertEquals(10, hits.get())
            assertEquals(10, breaker.metrics.numberOfFailedCalls)
            assertEquals(CircuitBreaker.State.OPEN, breaker.state)
            failure(HttpStatus.SERVICE_UNAVAILABLE) { admin.attended(ProgramSessionType.STANDARD, 7, 2) }
            failure(HttpStatus.SERVICE_UNAVAILABLE) { cancellation.find(UUID.randomUUID(), 2, "01012345678") }
            assertEquals(10, hits.get())
        }
    }

    @Test
    fun `빈 URL과 토큰은 탐색 없이 기동하며 기존 설정 누락 503을 반환한다`() {
        for ((url, token) in listOf(
            "" to "attendance-token",
            "   " to "attendance-token",
            "http://127.0.0.1:${server.address.port}" to "",
        )) {
            runner.withPropertyValues("application.attendance.url=$url", "application.attendance.internal-token=$token").run { context ->
                assertNull(context.startupFailure)
                failure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                ) { context.getBean(AdminAttendanceGateway::class.java).attended(ProgramSessionType.STANDARD, 7, 2) }
                failure(HttpStatus.SERVICE_UNAVAILABLE) {
                    context.getBean(AttendanceCancellationStatusGateway::class.java).find(UUID.randomUUID(), 2, "01012345678")
                }
            }
        }
        assertEquals(0, hits.get())
    }

    @Test
    fun `Feign GET 연결 실패는 내부 재시도 뒤에도 요청 한 건으로 집계한다`() {
        configured().withPropertyValues("application.attendance.url=http://127.0.0.1:1").run { context ->
            failure(
                HttpStatus.SERVICE_UNAVAILABLE,
            ) { context.getBean(AdminAttendanceGateway::class.java).attended(ProgramSessionType.STANDARD, 7, 2) }
            val metrics = context.getBean(DownstreamCircuits::class.java).breaker(Downstream.ATTENDANCE).metrics
            assertEquals(1, metrics.numberOfFailedCalls)
            assertEquals(1, metrics.numberOfBufferedCalls)
        }
    }

    @Test
    fun `Feign 8초 읽기 타임아웃은 재시도 없이 장애 한 건이다`() {
        hold = true
        configured().run { context ->
            failure(
                HttpStatus.SERVICE_UNAVAILABLE,
            ) { context.getBean(AdminAttendanceGateway::class.java).attended(ProgramSessionType.STANDARD, 7, 2) }
            assertEquals(1, hits.get())
            val metrics = context.getBean(DownstreamCircuits::class.java).breaker(Downstream.ATTENDANCE).metrics
            assertEquals(1, metrics.numberOfFailedCalls)
            assertEquals(1, metrics.numberOfBufferedCalls)
        }
    }

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableFeignClients(clients = [AttendanceClient::class])
    @EnableConfigurationProperties(AttendanceClientProperties::class)
    @Import(AdminAttendanceGateway::class, AttendanceCancellationStatusGateway::class)
    class Pilot {
        @Bean fun mapper() = jacksonObjectMapper()

        @Bean fun circuits() = DownstreamCircuits()
    }
}
