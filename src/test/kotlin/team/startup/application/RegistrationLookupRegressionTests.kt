package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.ManagedParticipantCommand
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOfferCommand
import team.startup.application.domain.application.presentation.dto.ParticipantQrCommand
import team.startup.application.domain.application.presentation.dto.ProgramGuardianPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import team.startup.application.domain.application.service.impl.ApplicationNotifications
import team.startup.application.domain.application.service.impl.FieldVisitEligibility
import team.startup.application.domain.application.service.impl.GetOwnerPromotionOffersServiceImpl
import team.startup.application.domain.application.service.impl.GetParticipantQrServiceImpl
import team.startup.application.domain.application.service.impl.ProgramFormAdapter
import team.startup.application.domain.application.service.impl.ProgramSessionAdmission
import team.startup.application.domain.application.service.impl.ProgramWaitlist
import team.startup.application.domain.application.service.impl.PromotionOffers
import team.startup.application.domain.application.service.impl.RegisterFieldVisitServiceImpl
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.StudentGuardianEligibilityAdapter
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
class RegistrationLookupRegressionTests {
    private val mapper = jacksonObjectMapper()
    private val expo = UUID.randomUUID()
    private val formId = UUID.randomUUID()
    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    private val phone = "01012345678"
    private val statements = AtomicInteger()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var writes: JdbcTemplate
    private lateinit var visits: FieldVisitRegistrationRepository
    private lateinit var transactions: TransactionTemplate
    private lateinit var server: HttpServer
    private lateinit var gateway: RegistrationGateway
    private var emptyParty = false
    private var cancelDuringAttendance = false
    private var managedWhileLocked = false
    private val now = Instant.parse("2026-10-29T01:00:00Z")
    private val definition =
        ProgramSessionDefinition(
            expo.toString(),
            ProgramSessionType.STANDARD,
            1,
            UUID.randomUUID(),
            1,
            ProgramSessionPolicy(
                formId,
                setOf("ELEMENTARY_STUDENT"),
                10,
                10,
                Instant.EPOCH,
                Instant.parse("2100-01-01T00:00:00Z"),
                false,
                Instant.parse("2100-01-01T00:00:00Z"),
                Instant.parse("2100-01-01T00:00:00Z"),
                guardianPolicy = ProgramGuardianPolicy(1, 101, "primary", 102, (1..6).associate { "g$it" to it }),
            ),
        )

    @BeforeEach
    fun setup() {
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway
            .configure()
            .dataSource(source)
            .load()
            .migrate()
        writes = JdbcTemplate(source)
        writes.execute("TRUNCATE tb_field_visit_registration, tb_field_visit_request CASCADE")
        val counted =
            object : AbstractDataSource() {
                override fun getConnection(): Connection {
                    val connection = source.connection
                    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                        if (method.name == "prepareStatement") statements.incrementAndGet()
                        try {
                            method.invoke(connection, *(args ?: emptyArray()))
                        } catch (exception: InvocationTargetException) {
                            throw exception.targetException
                        }
                    } as Connection
                }

                override fun getConnection(
                    username: String,
                    password: String,
                ): Connection = connection
            }
        jdbc = JdbcTemplate(counted)
        visits = FieldVisitRegistrationRepository(jdbc)
        transactions = TransactionTemplate(DataSourceTransactionManager(counted))
        writes.update(
            "INSERT INTO tb_field_visit_registration(" +
                "id,expo_id,participant_id,visit_date,status,generation,registered_at,delivery_pending) " +
                "SELECT gen_random_uuid(),?,n,?,'ACTIVE',1,CURRENT_TIMESTAMP,false FROM generate_series(1,5) n",
            expo,
            today,
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            exchange.requestBody.readAllBytes()
            calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val body =
                when {
                    path.endsWith("/resolve") -> {
                        """{"expoId":"$expo","phoneNumber":"$phone","expiresAt":"2100-01-01T00:00:00"}"""
                    }

                    path.endsWith("/managed/select") -> {
                        """[{"participantId":1,"occupation":"ELEMENTARY_STUDENT","applicationType":"FIELD"}]"""
                    }

                    path.endsWith("/managed") -> {
                        val hash = MessageDigest.getInstance("SHA-256").digest(phone.toByteArray()).joinToString("") { "%02x".format(it) }
                        source.connection.use { connection ->
                            connection.prepareStatement("SELECT pg_try_advisory_lock(75, hashtext(?))").use { statement ->
                                statement.setString(1, "$expo:$hash")
                                statement.executeQuery().use { result ->
                                    result.next()
                                    managedWhileLocked = !result.getBoolean(1)
                                }
                            }
                        }
                        val people =
                            if (emptyParty) {
                                emptyList()
                            } else {
                                (1..5).map {
                                    mapOf(
                                        "participantId" to it,
                                        "name" to "participant $it",
                                        "occupation" to "ELEMENTARY_STUDENT",
                                        "representative" to (it == 1),
                                        "applicationType" to "FIELD",
                                    )
                                }
                            }
                        mapper.writeValueAsString(mapOf("registered" to !emptyParty, "participants" to people))
                    }

                    path.contains("/forms/by-id/") -> {
                        """{"id":"$formId","expoId":"$expo","purpose":"PROGRAM","version":1,
                        "startDate":"1970-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":[
                        {"id":101,"title":"School","formType":"DROPDOWN","requiredStatus":true,"dynamicFormType":"DEFAULT","jsonData":{"primary":"Primary"},"otherJson":null},
                        {"id":102,"title":"Grade","formType":"DROPDOWN","requiredStatus":true,"dynamicFormType":"DEFAULT","jsonData":{${(1..6).joinToString {
                            "\"g$it\":\"$it\""
                        }}},"otherJson":{"hasEtc":false,"conditional":{"parentIndex":0,"triggerValue":"primary"}}}]}
                        """
                    }

                    path.contains("/standard-programs/") -> {
                        mapper.writeValueAsString(definition)
                    }

                    path.endsWith("/qr") -> {
                        if (cancelDuringAttendance) cancelVisit()
                        """{"participantId":1,"code":"qr-code"}"""
                    }

                    else -> {
                        """{"startedDay":"$today","finishedDay":"$today","participantPhoneVerificationRequired":false,"participantPhoneVerificationRevision":0}"""
                    }
                }
            exchange.responseHeaders.set("Connection", "close")
            if (exchange.requestMethod == "PUT") {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            } else {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        gateway = RegistrationGateway(mapper, url, url, url, "expo", "form", "user", true, url, "attendance", true, true, visits)
        statements.set(0)
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `현장 등록은 세션 번호를 한 번 해석하고 소유자 잠금 안에서 일행을 조회한다`() {
        val service =
            RegisterFieldVisitServiceImpl(
                gateway,
                visits,
                FieldVisitRequestRepository(jdbc, mapper),
                transactions,
                true,
                true,
                PreregisterSessionRepository(jdbc, mapper),
            )
        val receipt =
            service.execute(
                FieldVisitCommand(expo, "session", "request", RegisterFieldVisitRequest(listOf(1), emptyList(), true)),
            )
        assertEquals(listOf(1L), receipt.participants.map { it.participantId })
        assertEquals(1, callCount("/internal/participant-sessions/resolve"))
        assertTrue(managedWhileLocked)
    }

    @Test
    fun `제안 조회는 일행 조회의 인증 번호를 재사용한다`() {
        val service =
            GetOwnerPromotionOffersServiceImpl(
                gateway,
                PromotionOfferRepository(jdbc),
                mock(PromotionOffers::class.java),
                Clock.systemUTC(),
                requireNotNull(transactions.transactionManager),
            )
        assertTrue(service.execute(OwnerPromotionOfferCommand(expo, "session")).isEmpty())
        assertEquals(1, callCount("/internal/participant-sessions/resolve"))
        assertEquals(3, statements.get())
    }

    @Test
    fun `일행 방문을 한 번 읽고 날짜와 전달 취소 상태를 구분하며 번호는 응답에서 제외한다`() {
        writes.update("UPDATE tb_field_visit_registration SET cancellation_pending=true WHERE participant_id=2")
        writes.update("UPDATE tb_field_visit_registration SET delivery_pending=true WHERE participant_id=3")
        writes.update("UPDATE tb_field_visit_registration SET status='CANCELLED' WHERE participant_id=4")
        writes.update("UPDATE tb_field_visit_registration SET visit_date=? WHERE participant_id=5", today.minusDays(1))
        val party = gateway.managedParty(expo, "session")
        assertEquals(listOf(true, false, false, false, false), party.participants.map { it.fieldRegistered })
        assertEquals(1, statements.get())
        assertFalse(mapper.writeValueAsString(party).contains(phone))
        assertFalse(mapper.writeValueAsString(party).contains("phoneNumber"))
        emptyParty = true
        statements.set(0)
        assertTrue(gateway.managedParty(expo, "session").participants.isEmpty())
        assertEquals(0, statements.get())
    }

    @Test
    fun `선택 단계는 방문을 한 번 읽고 취소 대기는 User 선택 전에 거절한다`() {
        assertTrue(gateway.managedParticipant(expo.toString(), "session", 1).fieldRegistered)
        assertEquals(1, statements.get())
        cancelVisit()
        calls.clear()
        assertEquals(
            409,
            assertThrows<ResponseStatusException> { gateway.managedParticipant(expo.toString(), "session", 1) }.statusCode.value(),
        )
        assertEquals(0, callCount("/internal/standard-participants/managed/select"))
    }

    @Test
    fun `QR은 일행 일괄 조회와 앞뒤 한 문장 판정으로 세 번만 읽는다`() {
        assertEquals("qr-code", qr().execute(ParticipantQrCommand(ManagedParticipantCommand(expo, "session"), 1)).code)
        assertEquals(3, statements.get())
        assertEquals(1, callCount("/internal/expos/$expo/participants/1/qr"))
    }

    @Test
    fun `QR은 Attendance 앞과 뒤에 취소 상태를 모두 확인한다`() {
        cancelVisit()
        val command = ParticipantQrCommand(ManagedParticipantCommand(expo, "session"), 1)
        assertEquals(404, assertThrows<ResponseStatusException> { qr().execute(command) }.statusCode.value())
        assertEquals(0, callCount("/internal/expos/$expo/participants/1/qr"))
        writes.update("UPDATE tb_field_visit_registration SET cancellation_pending=false WHERE participant_id=1")
        cancelDuringAttendance = true
        assertEquals(404, assertThrows<ResponseStatusException> { qr().execute(command) }.statusCode.value())
        assertEquals(1, callCount("/internal/expos/$expo/participants/1/qr"))
    }

    @Test
    fun `보호자 정책 단독 신청은 검증한 폼을 한 번만 읽고 잠금 안 방문 확인을 유지한다`() {
        assertEquals("CONFIRMED", admission(mock(ProgramSessionApplicationRepository::class.java)).admit(command(), false).status)
        assertEquals(1, callCount("/internal/forms/by-id/$formId"))
        assertEquals(2, statements.get())
    }

    @Test
    fun `폼 검증 뒤 잠금 획득 중 방문이 취소되면 최종 확인에서 거절한다`() {
        val ledger = mock(ProgramSessionApplicationRepository::class.java)
        doAnswer {
            cancelVisit()
            null
        }.`when`(ledger).lockParticipant(anyLong())
        assertEquals(409, assertThrows<ResponseStatusException> { admission(ledger).admit(command(), false) }.statusCode.value())
        assertEquals(1, callCount("/internal/forms/by-id/$formId"))
    }

    @Test
    fun `QR 단일 문장은 레거시 취소 표시와 신규 방문 완료 판정을 구분한다`() {
        val legacy = ManagedParticipantRepository(jdbc, false)
        assertTrue(legacy.qrAvailable(expo, 1, 1, true))
        legacy.revokeFieldQr(expo, 1)
        assertFalse(legacy.qrAvailable(expo, 1, 1, true))
        val current = ManagedParticipantRepository(jdbc, true)
        statements.set(0)
        assertTrue(current.qrAvailable(expo, 1, 1, false))
        assertEquals(1, statements.get())
        writes.update("UPDATE tb_field_visit_registration SET delivery_pending=true WHERE participant_id=1")
        assertFalse(current.qrAvailable(expo, 1, 1, true))
    }

    private fun admission(ledger: ProgramSessionApplicationRepository): ProgramSessionAdmission {
        val forms = ProgramFormAdapter(gateway)
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("forms", forms)
        beans.registerSingleton("guardian", StudentGuardianEligibilityAdapter(gateway, forms, jdbc, mapper))
        val waitlist = mock(ProgramWaitlist::class.java)
        `when`(waitlist.now()).thenReturn(now)
        return ProgramSessionAdmission(
            ledger,
            mock(StandardProgramApplicationRepository::class.java),
            mock(TrainingProgramApplicationRepository::class.java),
            mock(TrainingOperationRepository::class.java),
            gateway,
            beans.getBeanProvider(ProgramFormProvider::class.java),
            waitlist,
            mock(ProgramWaitlistRepository::class.java),
            beans.getBeanProvider(StudentGuardianEligibilityProvider::class.java),
            transactions,
            mock(ApplicationNotifications::class.java),
            FieldVisitEligibility(visits, true),
        )
    }

    private fun command() =
        ApplyProgramSessionCommand(
            expo.toString(),
            ProgramSessionType.STANDARD,
            1,
            1,
            1,
            mapper.readTree("""{"101":"primary","102":"g4"}"""),
            "session",
            "request",
        )

    private fun qr() =
        GetParticipantQrServiceImpl(
            gateway,
            ManagedParticipantRepository(jdbc, true),
            true,
            requireNotNull(transactions.transactionManager),
        )

    private fun cancelVisit() = writes.update("UPDATE tb_field_visit_registration SET cancellation_pending=true WHERE participant_id=1")

    private fun callCount(path: String) = calls[path]?.get() ?: 0

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
