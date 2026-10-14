package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.service.impl.ApplicationPageVisitorPurgeScheduler
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ApplicationPageViewHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var purge: ApplicationPageVisitorPurgeScheduler

    private fun view(
        expoId: Any,
        body: String,
    ) = mvc
        .perform(post("/application/expos/$expoId/page-views").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .response.status

    private fun admin(
        expoId: UUID,
        authorization: String? = "Bearer admin-jwt",
    ) = mvc
        .perform(
            get("/application/admin/expos/$expoId/page-views").apply { if (authorization != null) header("Authorization", authorization) },
        ).andReturn()
        .response

    @Test
    fun `신청화면 PV는 새로고침마다 늘고 UV는 같은 visitorId를 하루 한 번 센다`() {
        val expoId = UUID.randomUUID()
        val visitor = UUID.randomUUID()
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        jdbc.update(
            "INSERT INTO tb_application_page_view_daily (expo_id, view_date, page_views, unique_visitors) VALUES (?, ?, 7, 3)",
            expoId,
            today.minusDays(1),
        )
        // Concurrent refreshes by one visitor must still count once.
        (1..20)
            .map { CompletableFuture.supplyAsync { view(expoId, """{"visitorId":"$visitor"}""") } }
            .forEach { assertEquals(204, it.join()) }
        assertEquals(204, view(expoId, """{"visitorId":"${UUID.randomUUID()}"}"""))
        assertEquals(204, view(UUID.randomUUID(), """{"visitorId":"$visitor"}"""))
        listOf("{}", """{"visitorId":"not-a-uuid"}""").forEach { assertEquals(400, view(expoId, it), it) }
        assertEquals(400, view("not-a-uuid", """{"visitorId":"$visitor"}"""))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_application_page_visitor WHERE position(?::bytea IN visitor_hash) > 0",
                Int::class.java,
                visitor.toString(),
            ),
        )

        assertEquals(401, admin(expoId, null).status)
        expoStatus = 403
        assertEquals(403, admin(expoId).status)
        expoStatus = 200
        val response = admin(expoId)
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(today.toString(), body["today"]["date"].asString())
        assertEquals(21, body["today"]["pageViews"].asInt())
        assertEquals(2, body["today"]["uniqueVisitors"].asInt())
        assertEquals(28, body["totalPageViews"].asInt())
        assertEquals(listOf(today.minusDays(1).toString(), today.toString()), body["daily"].toList().map { it["date"].asString() })
        assertEquals(listOf(3, 2), body["daily"].toList().map { it["uniqueVisitors"].asInt() })

        val empty = mapper.readTree(admin(UUID.randomUUID()).contentAsString)
        assertEquals(0, empty["today"]["pageViews"].asInt())
        assertEquals(0, empty["daily"].size())
    }

    @Test
    fun `방문자 해시는 KST 일자 종료 48시간 뒤 지우고 일별 합계는 유지한다`() {
        val expoId = UUID.randomUUID()
        val days = listOf(LocalDate.parse("2001-10-10"), LocalDate.parse("2001-10-11"))
        days.forEach { day ->
            jdbc.update(
                "INSERT INTO tb_application_page_view_daily (expo_id, view_date, page_views, unique_visitors) VALUES (?, ?, 5, 1)",
                expoId,
                day,
            )
            jdbc.update("INSERT INTO tb_application_page_visitor (expo_id, view_date, visitor_hash) VALUES (?, ?, '\\x01')", expoId, day)
        }

        fun remaining() =
            jdbc.queryForList(
                "SELECT view_date FROM tb_application_page_visitor WHERE expo_id = ? ORDER BY view_date",
                LocalDate::class.java,
                expoId,
            )

        val boundary = OffsetDateTime.parse("2001-10-14T00:00:00+09:00").toInstant()
        purge.purge(boundary.minusSeconds(1))
        assertEquals(listOf(days[1]), remaining())
        purge.purge(boundary)
        assertEquals(emptyList<LocalDate>(), remaining())
        val body = mapper.readTree(admin(expoId).contentAsString)
        assertEquals(10, body["totalPageViews"].asInt())
        assertEquals(listOf(1, 1), body["daily"].toList().map { it["uniqueVisitors"].asInt() })
    }

    companion object {
        private var expoStatus = 200
        private val expo =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expo/") { exchange ->
                    val id = exchange.requestURI.path.split("/")[3]
                    val body =
                        """{"expoId":"$id","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}"""
                            .toByteArray()
                    exchange.sendResponseHeaders(expoStatus, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${expo.address.port}" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
        }

        @AfterAll
        @JvmStatic
        fun stop() = expo.stop(0)

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
