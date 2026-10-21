package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramGuardianPolicy
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason.GRADE_INVALID
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason.GUARDIAN_LINK_INVALID
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason.GUARDIAN_REQUIRED
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason.OCCUPATION_NOT_ALLOWED
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason.SCHOOL_TYPE_MISMATCH
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.StudentGuardianEligibilityQuery
import team.startup.application.domain.application.presentation.dto.StudentGuardianLink
import team.startup.application.domain.application.presentation.dto.StudentGuardianSubmission
import team.startup.application.domain.application.presentation.dto.StudentGuardianValidationPhase
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import team.startup.application.domain.application.service.impl.ProgramFormAdapter
import team.startup.application.domain.application.service.impl.ProgramFormAdapterConfig
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.StudentGuardianEligibilityAdapter
import team.startup.application.domain.application.service.impl.StudentGuardianEligibilityAdapterConfig
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class StudentGuardianEligibilityAdapterTests {
    private val mapper = jacksonObjectMapper()
    private val expo = UUID.randomUUID().toString()
    private val formId = UUID.randomUUID()
    private val guardian = ProgramGuardianPolicy(1, 101, "primary", 102, (1..6).associate { "g$it" to it })
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private val end = Instant.parse("2026-11-01T00:00:00Z")
    private var definition =
        ProgramSessionDefinition(
            expo,
            ProgramSessionType.STANDARD,
            1,
            UUID.randomUUID(),
            1,
            ProgramSessionPolicy(
                formId,
                setOf("ELEMENTARY_STUDENT", "PARENT", "TEACHER"),
                10,
                10,
                start,
                end,
                false,
                end,
                end,
                guardianPolicy = guardian,
            ),
        )
    private lateinit var server: HttpServer
    private lateinit var gateway: RegistrationGateway
    private lateinit var adapter: StudentGuardianEligibilityAdapter
    private val calls = mutableListOf<Pair<String, String?>>()
    private val roles = mutableMapOf(1L to "ELEMENTARY_STUDENT", 2L to "ELEMENTARY_STUDENT", 3L to "PARENT")
    private var roleResponse: String? = null
    private var roleStatus = 200
    private var formVersion = 1L
    private var questions = ""
    private val saved = mutableMapOf<Long, StudentGuardianSubmission>()
    private var savedReads = 0
    private var supplierFailure: Throwable? = null
    private val jdbc =
        mock(JdbcTemplate::class.java) { call ->
            if (call.method.name == "query") {
                savedReads++
                val arguments = call.rawArguments[2] as Array<*>
                assertEquals(UUID.fromString(expo), arguments[0])
                assertEquals(definition.programType.name, arguments[1])
                assertEquals(definition.id, arguments[2])
                val item = saved[arguments[3] as Long]
                if (item == null) {
                    emptyList<Any>()
                } else {
                    val rs =
                        mock(ResultSet::class.java) { read ->
                            when (read.method.name) {
                                "getLong" -> item.formVersion
                                "getString" -> item.answers.toString()
                                else -> null
                            }
                        }
                    listOf(call.getArgument<RowMapper<*>>(1).mapRow(rs, 0))
                }
            } else {
                null
            }
        }

    @BeforeEach
    fun setup() {
        questions = """[
          {"id":101,"title":"Renamed school","formType":"DROPDOWN","requiredStatus":true,"dynamicFormType":"DEFAULT",
           "jsonData":{"primary":"Unrelated label","other":"Other","guardian":"Guardian"},"otherJson":null},
          {"id":102,"title":"Renamed grade","formType":"DROPDOWN","requiredStatus":true,"dynamicFormType":"DEFAULT",
           "jsonData":{${(1..6).joinToString { "\"g$it\":\"label $it\"" }}},
           "otherJson":{"hasEtc":false,"conditional":{"parentIndex":0,"triggerValue":"primary"}}}
        ]"""
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    try {
                        val path = exchange.requestURI.path
                        calls.add(path to exchange.requestHeaders.getFirst("X-Internal-Token"))
                        val body = exchange.requestBody.bufferedReader().readText()
                        val response =
                            when {
                                path.endsWith("/standard-participants/details") -> {
                                    assertEquals(expo, mapper.readTree(body).path("expoId").asString())
                                    roleResponse ?: mapper.writeValueAsString(
                                        mapper.readTree(body).path("participantIds").toList().map {
                                            mapOf("participantId" to it.asLong(), "occupation" to roles[it.asLong()])
                                        },
                                    )
                                }

                                path.endsWith("/managed/select") -> {
                                    """[{"participantId":1,"occupation":"${roles[1]}"}]"""
                                }

                                path.endsWith("/resolve") -> {
                                    """{"expoId":"$expo","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                                }

                                path.contains("/forms/by-id/") -> {
                                    """{"id":"$formId","expoId":"$expo","purpose":"PROGRAM","version":$formVersion,
                        "startDate":"$start","endDate":"$end","dynamicForm":$questions}"""
                                }

                                else -> {
                                    mapper.writeValueAsString(definition)
                                }
                            }
                        val bytes = response.toByteArray()
                        exchange.sendResponseHeaders(if (path.endsWith("/details")) roleStatus else 200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    } catch (failure: Throwable) {
                        supplierFailure = failure
                        exchange.close()
                    }
                }
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}"
        gateway = RegistrationGateway(mapper, url, url, url, "expo-token", "form-token", "user-token", programSessionsEnabled = true)
        adapter = StudentGuardianEligibilityAdapter(gateway, ProgramFormAdapter(gateway), jdbc, mapper)
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        supplierFailure?.let { throw AssertionError("Mock supplier failed", it) }
    }

    @Test
    fun `production bean is available and an explicit provider is preserved`() {
        val runner =
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(ProgramFormAdapterConfig::class.java, StudentGuardianEligibilityAdapterConfig::class.java),
                ).withBean(RegistrationGateway::class.java, { gateway })
                .withBean(JdbcTemplate::class.java, { jdbc })
                .withBean(ObjectMapper::class.java, { mapper })
        runner.run { context ->
            assertEquals(
                StudentGuardianEligibilityAdapter::class.java,
                context.getBean(StudentGuardianEligibilityProvider::class.java).javaClass,
            )
        }
        runner.withBean("custom", StudentGuardianEligibilityProvider::class.java, { adapter }).run { context ->
            assertEquals(mapOf("custom" to adapter), context.getBeansOfType(StudentGuardianEligibilityProvider::class.java))
        }
    }

    @Test
    fun `two students use structured option keys and actual User parent role with target tokens`() {
        adapter.validate(query())
        assertTrue(calls.contains("/internal/standard-participants/details" to "user-token"))
        assertTrue(calls.contains("/internal/forms/by-id/$formId" to "form-token"))
        assertEquals(0, savedReads)
    }

    @Test
    fun `all six grades are distinguished and missing or forged grade never defaults to lower grade`() {
        (1..3).forEach { adapter.validate(query(student = answers("g$it"))) }
        (4..6).forEach { forbidden(GRADE_INVALID) { adapter.validate(query(student = answers("g$it"))) } }
        forbidden(SCHOOL_TYPE_MISMATCH) { adapter.validate(query(student = mapper.readTree("""{"101":"other"}"""))) }
        assertThrows(ResponseStatusException::class.java) { adapter.validate(query(student = mapper.readTree("""{"101":"primary"}"""))) }
        assertThrows(ResponseStatusException::class.java) { adapter.validate(query(student = answers("1학년"))) }
    }

    @Test
    fun `representative status does not grant guardian eligibility and children cannot act as parents`() {
        roles[3] = "TEACHER"
        forbidden(GUARDIAN_LINK_INVALID) { adapter.validate(query()) }
        roles[3] = "ELEMENTARY_STUDENT"
        forbidden(GUARDIAN_LINK_INVALID) { adapter.validate(query()) }
        roles[3] = "PARENT"
        roles[1] = "PARENT"
        forbidden(GUARDIAN_LINK_INVALID) { adapter.validate(query()) }
        roles[1] = "ELEMENTARY_STUDENT"
        definition = definition.copy(policy = requireNotNull(definition.policy).copy(allowedOccupations = setOf("ELEMENTARY_STUDENT")))
        forbidden(OCCUPATION_NOT_ALLOWED) { adapter.validate(query()) }
    }

    @Test
    fun `version and question reference changes fail closed`() {
        formVersion = 2
        assertThrows(ProgramApplicationConflictException::class.java) { adapter.validate(query()) }
        formVersion = 1
        questions = questions.replace("\"parentIndex\":0", "\"parentIndex\":1")
        assertThrows(ResponseStatusException::class.java) { adapter.validate(query()) }
    }

    @Test
    fun `malformed or missing User identities and roles never pass validation`() {
        listOf("[]", """[{"participantId":999,"occupation":"PARENT"}]""", """[{}, {}, {}]""").forEach {
            roleResponse = it
            assertEquals(502, assertThrows(ResponseStatusException::class.java) { adapter.validate(query()) }.statusCode.value())
        }
        roleResponse = null
        roles.remove(3)
        forbidden(GUARDIAN_LINK_INVALID) { adapter.validate(query()) }
        roleStatus = 503
        assertThrows(ResponseStatusException::class.java) { adapter.validate(query()) }
    }

    @Test
    fun `promotion reads original answers and revalidates current roles and form version`() {
        val original = query()
        original.submissions.forEach { saved[it.participantId] = it }
        val promotion = original.copy(phase = StudentGuardianValidationPhase.PROMOTION, submissions = emptyList())
        adapter.validate(promotion)
        assertEquals(3, savedReads)
        roles[3] = "TEACHER"
        forbidden(GUARDIAN_LINK_INVALID) { adapter.validate(promotion) }
        roles[3] = "PARENT"
        saved[1] = StudentGuardianSubmission(1, 1, answers("g4"))
        forbidden(GRADE_INVALID) { adapter.validate(promotion) }
        saved[1] = original.submissions.first()
        formVersion = 2
        assertThrows(ProgramApplicationConflictException::class.java) { adapter.validate(promotion) }
    }

    @Test
    fun `reused guardian is validated from the existing application without requiring another submission`() {
        saved[3] = StudentGuardianSubmission(3, 1, mapper.readTree("""{"101":"other"}"""))
        adapter.validate(query().copy(reusedGuardianIds = setOf(3), submissions = query().submissions.filter { it.participantId != 3L }))
        assertEquals(1, savedReads)
        saved.clear()
        forbidden(GUARDIAN_LINK_INVALID) { adapter.validate(query().copy(reusedGuardianIds = setOf(3))) }
    }

    @Test
    fun `single lower grade and standalone parent are blocked but upper grades and policy OFF keep single route`() {
        conflict(GUARDIAN_REQUIRED) { adapter.validateSingle(single("g1"), definition, roles[1]) }
        (4..6).forEach { adapter.validateSingle(single("g$it"), definition, roles[1]) }
        roles[1] = "PARENT"
        conflict(GUARDIAN_LINK_INVALID) { adapter.validateSingle(single("g4"), definition, roles[1]) }
        definition = definition.copy(policy = requireNotNull(definition.policy).copy(guardianPolicy = null))
        adapter.validateSingle(single("g1"), definition, roles[1])
        assertThrows(ProgramApplicationConflictException::class.java) { adapter.validate(query()) }
    }

    @Test
    fun `elementary occupation cannot hide grade by selecting another school type`() {
        forbidden(SCHOOL_TYPE_MISMATCH) {
            adapter.validateSingle(single("g1").copy(answers = mapper.readTree("""{"101":"other"}""")), definition, roles[1])
        }
    }

    @Test
    fun `another occupation cannot bypass guardian rules with elementary answers`() {
        forbidden(SCHOOL_TYPE_MISMATCH) { adapter.validateSingle(single("g1"), definition, "GENERAL") }
        adapter.validateSingle(single("g1").copy(answers = mapper.readTree("""{"101":"other"}""")), definition, "GENERAL")
    }

    @Test
    fun `guardian school value requires parents to choose it and blocks single applicants from choosing it`() {
        definition =
            definition.copy(
                policy = requireNotNull(definition.policy).copy(guardianPolicy = guardian.copy(guardianSchoolValue = "guardian")),
            )
        forbidden(SCHOOL_TYPE_MISMATCH) { adapter.validate(query()) }
        adapter.validate(query(guardianAnswer = "guardian"))
        forbidden(SCHOOL_TYPE_MISMATCH) {
            adapter.validateSingle(single("g4").copy(answers = mapper.readTree("""{"101":"guardian"}""")), definition, "GENERAL")
        }
        adapter.validateSingle(single("g4").copy(answers = mapper.readTree("""{"101":"other"}""")), definition, "GENERAL")
    }

    @Test
    fun `guardian school value must be a distinct existing school option`() {
        assertTrue(guardian.valid())
        assertTrue(!guardian.copy(guardianSchoolValue = " ").valid())
        assertTrue(!guardian.copy(guardianSchoolValue = "primary").valid())
        definition =
            definition.copy(
                policy = requireNotNull(definition.policy).copy(guardianPolicy = guardian.copy(guardianSchoolValue = "missing")),
            )
        assertEquals(502, assertThrows(ResponseStatusException::class.java) { adapter.validate(query()) }.statusCode.value())
    }

    private fun query(
        student: tools.jackson.databind.JsonNode = answers("g1"),
        guardianAnswer: String = "other",
    ) = StudentGuardianEligibilityQuery(
        definition,
        setOf(1, 2, 3),
        listOf(StudentGuardianLink(1, 3), StudentGuardianLink(2, 3)),
        StudentGuardianValidationPhase.SUBMISSION,
        submissions =
            listOf(
                StudentGuardianSubmission(1, 1, student),
                StudentGuardianSubmission(2, 1, answers("g3")),
                StudentGuardianSubmission(3, 1, mapper.readTree("""{"101":"$guardianAnswer"}""")),
            ),
    )

    private fun answers(grade: String) = mapper.readTree("""{"101":"primary","102":"$grade"}""")

    private fun single(grade: String) = ApplyProgramSessionCommand(expo, definition.programType, 1, 1, 1, answers(grade), "session", "key")

    // 403 사유는 핸들러가 enum 이름으로 읽는 reason에 싣는다(#271).
    private fun forbidden(
        reason: ProgramIneligibleReason,
        block: () -> Unit,
    ) {
        val ex = assertThrows(ResponseStatusException::class.java, block)
        assertEquals(403 to reason.name, ex.statusCode.value() to ex.reason)
    }

    private fun conflict(
        reason: ProgramIneligibleReason,
        block: () -> Unit,
    ) {
        assertEquals(reason, assertThrows(ProgramApplicationConflictException::class.java, block).ineligibleReason)
    }
}
