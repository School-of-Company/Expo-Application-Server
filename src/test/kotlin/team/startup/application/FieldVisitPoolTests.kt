package team.startup.application

import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.http.Cookie
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.FieldVisitController
import team.startup.application.domain.application.presentation.InternalFieldVisitController
import team.startup.application.domain.application.presentation.dto.FieldVisitParty
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.LegacyTraineeAttributionRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.service.GetFieldVisitPartyService
import team.startup.application.domain.application.service.impl.GetFieldVisitSourceServiceImpl
import team.startup.application.domain.application.service.impl.RegisterFieldVisitServiceImpl
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class FieldVisitPoolTests {
    private val mapper = jacksonObjectMapper()

    private fun pool(size: Int) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = size
                minimumIdle = size
                connectionTimeout = 30_000
            },
        )

    @Test
    fun `풀 크기별 진입 상한에서 복구는 연결을 얻지 않고 건너뛰고 등록은 503으로 끝난다`() {
        for (size in listOf(5, 15)) {
            pool(size).use { ds ->
                val requests = FieldVisitRequestRepository(JdbcTemplate(ds), mapper)
                val limit = (size - 1) / 2
                val entered = CountDownLatch(limit)
                val release = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(limit)
                try {
                    val holders =
                        (1..limit).map { index ->
                            executor.submit {
                                requests.withLock(UUID.randomUUID(), index.toString()) {
                                    entered.countDown()
                                    check(release.await(20, TimeUnit.SECONDS))
                                }
                            }
                        }
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    assertEquals(limit, ds.hikariPoolMXBean.activeConnections)
                    val started = System.nanoTime()
                    assertNull(requests.tryWithLock(UUID.randomUUID(), "recovery") { error("must skip") })
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
                    assertEquals(limit, ds.hikariPoolMXBean.activeConnections)
                    Executors.newSingleThreadExecutor().use { interrupted ->
                        interrupted
                            .submit {
                                Thread.currentThread().interrupt()
                                val failure =
                                    assertThrows<ResponseStatusException> {
                                        requests.withLock(UUID.randomUUID(), "interrupted") { error("must reject") }
                                    }
                                assertEquals(503, failure.statusCode.value())
                                assertTrue(Thread.currentThread().isInterrupted)
                            }.get(5, TimeUnit.SECONDS)
                    }
                    val failure =
                        assertThrows<ResponseStatusException> {
                            requests.withLock(UUID.randomUUID(), "overflow") { error("must reject") }
                        }
                    assertEquals(503, failure.statusCode.value())
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 10_000)
                    release.countDown()
                    holders.forEach { it.get(10, TimeUnit.SECONDS) }
                    repeat(limit + 1) {
                        assertThrows<IllegalStateException> {
                            requests.tryWithLock(UUID.randomUUID(), "failure") { error("release permit") }
                        }
                    }
                    assertEquals("recovered", requests.tryWithLock(UUID.randomUUID(), "recovery") { "recovered" })
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `느린 공급자와 서로 다른 번호 20건이 풀 15를 고갈시키지 않는다`() {
        pool(15).use { ds ->
            Flyway
                .configure()
                .dataSource(ds)
                .load()
                .migrate()
            val jdbc = JdbcTemplate(ds)
            val expo = UUID.randomUUID()
            val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
            jdbc.update(
                "INSERT INTO tb_field_visit_registration(id,expo_id,participant_id,visit_date,status,generation," +
                    "registered_at,delivery_pending) " +
                    "SELECT md5(? || i::text)::uuid,?,1000000+i,?,'ACTIVE',1,now(),false FROM generate_series(1,10000) i",
                expo.toString(),
                expo,
                today,
            )
            jdbc.update(
                "INSERT INTO tb_field_visit_event(operation_id,registration_id,generation,event_type) " +
                    "SELECT id,id,1,'REGISTERED' FROM tb_field_visit_registration WHERE expo_id=?",
                expo,
            )
            jdbc.update(
                "INSERT INTO tb_field_visit_request(expo_id,request_id,owner_hash,visit_date,command,receipt) " +
                    "SELECT ?,'seed-' || i::text,md5(i::text),?,'{}'::jsonb,'{}'::jsonb FROM generate_series(1,10000) i",
                expo,
                today,
            )
            val entered = CountDownLatch(7)
            val httpExecutor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = httpExecutor
            server.createContext("/") { exchange ->
                try {
                    val path = exchange.requestURI.path
                    val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val node = body.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
                    if (path.endsWith("/managed")) entered.countDown()
                    Thread.sleep(500)
                    val response =
                        when {
                            path.endsWith("/resolve") -> {
                                val id = requireNotNull(node).path("sessionId").asString().toInt()
                                mapper.writeValueAsString(
                                    mapOf(
                                        "expoId" to expo,
                                        "phoneNumber" to "010${id.toString().padStart(8, '0')}",
                                        "expiresAt" to "2099-01-01T00:00:00",
                                    ),
                                )
                            }

                            path.endsWith("/managed") -> {
                                val id =
                                    requireNotNull(node)
                                        .path("phoneNumber")
                                        .asString()
                                        .takeLast(8)
                                        .toLong()
                                """
                                {"registered":true,"participants":[
                                  {"participantId":$id,"name":"person","occupation":"GENERAL",
                                   "representative":true,"applicationType":"FIELD"}]}
                                """.trimIndent()
                            }

                            path == "/internal/expo/$expo" -> {
                                """
                                {"startedDay":"$today","finishedDay":"$today",
                                 "participantPhoneVerificationRequired":true,"participantPhoneVerificationRevision":1}
                                """.trimIndent()
                            }

                            else -> {
                                ""
                            }
                        }
                    if (response.isEmpty()) {
                        exchange.sendResponseHeaders(204, -1)
                    } else {
                        val bytes = response.toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
            val executor = Executors.newFixedThreadPool(21)
            try {
                val url = "http://127.0.0.1:${server.address.port}"
                val visits = FieldVisitRegistrationRepository(jdbc)
                val gateway =
                    RegistrationGateway(
                        mapper,
                        url,
                        url,
                        url,
                        "token",
                        "token",
                        "token",
                        attendanceUrl = url,
                        attendanceToken = "token",
                        registrationEnabled = true,
                        fieldVisitsEnabled = true,
                        fieldVisits = visits,
                    )
                val workflow =
                    RegisterFieldVisitServiceImpl(
                        gateway,
                        visits,
                        FieldVisitRequestRepository(jdbc, mapper),
                        TransactionTemplate(DataSourceTransactionManager(ds)),
                        true,
                        true,
                        PreregisterSessionRepository(jdbc, mapper),
                        ProgramSessionApplicationRepository(jdbc, mapper, LegacyTraineeAttributionRepository(jdbc, mapper, ds), gateway),
                    )
                val mvc =
                    MockMvcBuilders
                        .standaloneSetup(
                            FieldVisitController(
                                object : GetFieldVisitPartyService {
                                    override fun execute(command: GetOwnerApplicationsCommand) = FieldVisitParty(today, emptyList())
                                },
                                workflow,
                                "PARTICIPANT_SESSION",
                            ),
                            InternalFieldVisitController(GetFieldVisitSourceServiceImpl(visits)),
                        ).build()
                val start = CountDownLatch(1)
                val registrations =
                    (1..20).map { id ->
                        executor.submit<Pair<Long, Int>> {
                            start.await()
                            val began = System.nanoTime()
                            val response =
                                mvc
                                    .perform(
                                        post("/application/expos/$expo/field-visits")
                                            .cookie(Cookie("PARTICIPANT_SESSION", id.toString()))
                                            .header("Idempotency-Key", "key-$id")
                                            .contentType("application/json")
                                            .content("""{"participantIds":[$id],"personalInformationStatus":true}"""),
                                    ).andReturn()
                                    .response
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) to response.status
                        }
                    }
                start.countDown()
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                val probe =
                    executor.submit<Long> {
                        val began = System.nanoTime()
                        assertEquals(
                            200,
                            mvc
                                .perform(get("/internal/expos/$expo/field-visits"))
                                .andReturn()
                                .response.status,
                        )
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                    }
                val probeMs = probe.get(10, TimeUnit.SECONDS)
                assertTrue(probeMs < 10_000)
                val results = registrations.map { it.get(20, TimeUnit.SECONDS) }
                assertTrue(results.all { it.second == 200 || it.second == 503 })
                assertTrue(results.count { it.second == 200 } >= 7)
                assertEquals(
                    results.count { it.second == 200 },
                    jdbc.queryForObject(
                        "SELECT count(*) FROM tb_field_visit_request WHERE expo_id=? AND request_id LIKE 'key-%' AND receipt IS NOT NULL",
                        Int::class.java,
                        expo,
                    ),
                )
                val success = results.count { it.second == 200 }
                val overload = results.count { it.second == 503 }
                val p95Ms = results.map { it.first }.sorted()[18]
                LoggerFactory.getLogger(javaClass).info(
                    "Field visit burst: success={}, overload={}, p95Ms={}, unrelatedGetMs={}",
                    success,
                    overload,
                    p95Ms,
                    probeMs,
                )
            } finally {
                executor.shutdownNow()
                server.stop(0)
                httpExecutor.shutdownNow()
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
