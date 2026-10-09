package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import team.startup.application.domain.application.repository.SessionAttendanceMessage
import team.startup.application.domain.application.service.impl.SessionAttendanceGateway
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID

class SessionAttendanceGatewayTests {
    @Test
    fun `주소와 토큰이 없으면 발송 설정을 거부한다`() {
        for (url in listOf("", "file:///tmp/attendance", "http://localhost?token=secret", "http://localhost#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { SessionAttendanceGateway(url, "token") }
        }
        assertThrows(IllegalArgumentException::class.java) { SessionAttendanceGateway("http://localhost", " ") }
    }

    @Test
    fun `HTTP 헤더로 보낼 수 없는 토큰은 시작 시 비밀 값 없이 거부한다`() {
        for (token in listOf("secret\nvalue", "secret\rvalue", "secret\u0000value", "secret\u0100value")) {
            val ex = assertThrows(IllegalArgumentException::class.java) { SessionAttendanceGateway("http://localhost", token) }
            org.junit.jupiter.api.Assertions.assertEquals(
                "application.attendance.internal-token must be a valid HTTP header value",
                ex.message,
            )
            org.junit.jupiter.api.Assertions
                .assertNull(ex.cause)
        }
    }

    @Test
    fun `연결 거부는 재시도할 수 있는 IO 실패다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}"
        server.start()
        server.stop(0)
        assertThrows(IOException::class.java) { SessionAttendanceGateway(url, "token").send(message()) }
    }

    @Test
    fun `리다이렉트를 따라가 토큰을 다른 주소로 보내지 않는다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:1/token-leak")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val ex =
                assertThrows(IOException::class.java) {
                    SessionAttendanceGateway("http://127.0.0.1:${server.address.port}", "token").send(message())
                }
            org.junit.jupiter.api.Assertions
                .assertEquals("Attendance HTTP 302", ex.message)
        } finally {
            server.stop(0)
        }
    }

    private fun message() = SessionAttendanceMessage(1, UUID.randomUUID(), 42, 1, 0)
}
