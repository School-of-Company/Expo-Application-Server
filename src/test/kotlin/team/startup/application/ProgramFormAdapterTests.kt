package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.impl.ProgramFormAdapter
import team.startup.application.domain.application.service.impl.ProgramFormAdapterConfig
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.UUID

class ProgramFormAdapterTests {
    private val mapper = ObjectMapper()
    private val expo = UUID.randomUUID().toString()
    private val formId = UUID.randomUUID()
    private lateinit var server: HttpServer
    private lateinit var adapter: ProgramFormAdapter
    private var response = ""
    private var status = 200
    private var path: String? = null
    private var token: String? = null
    private val fields =
        """
        [
          {"id":101,"title":"Choice","formType":"DROPDOWN","requiredStatus":true,
           "jsonData":{"a":"First","b":"Second"},"otherJson":null,"dynamicFormType":"DEFAULT"},
          {"id":102,"title":"Details","formType":"SENTENCE","requiredStatus":true,"jsonData":{},
           "otherJson":{"hasEtc":false,"conditional":{"parentIndex":0,"triggerValue":"b"}},"dynamicFormType":"DEFAULT"},
          {"id":103,"title":"Several","formType":"MULTIPLE","requiredStatus":false,
           "jsonData":{"x":{"value":"Fixed","isAlwaysSelected":true},"y":"Second"},
           "otherJson":{"hasEtc":false,"maxSelection":1},"dynamicFormType":"DEFAULT"},
          {"id":104,"title":"Consent","formType":"CHECKBOX","requiredStatus":true,
           "jsonData":{},"otherJson":null,"dynamicFormType":"DEFAULT"}
        ]
        """.trimIndent()

    @BeforeEach
    fun setup() {
        response = definition()
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    path = exchange.requestURI.path
                    token = exchange.requestHeaders.getFirst("X-Internal-Token")
                    val bytes = response.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        adapter =
            ProgramFormAdapter(
                RegistrationGateway(
                    mapper,
                    "",
                    "http://127.0.0.1:${server.address.port}",
                    "",
                    "app-token",
                    formInternalToken = "form-token",
                ),
            )
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `production adapter is registered only when no explicit provider exists`() {
        val gateway = RegistrationGateway(mapper, "", "", "", "token")
        val runner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProgramFormAdapterConfig::class.java))
                .withBean(RegistrationGateway::class.java, { gateway })
        runner.run { context ->
            assertEquals(1, context.getBeansOfType(ProgramFormProvider::class.java).size)
            assertEquals(ProgramFormAdapter::class.java, context.getBean(ProgramFormProvider::class.java).javaClass)
        }
        runner.withBean("explicit", ProgramFormProvider::class.java, { adapter }).run { context ->
            assertEquals(mapOf("explicit" to adapter), context.getBeansOfType(ProgramFormProvider::class.java))
        }
    }

    @Test
    fun `real Form route validates ID answers and retains the exact definition`() {
        val snapshot = load("""{"101":"b","102":"text","103":["x"],"104":false}""")
        assertEquals("/internal/forms/by-id/$formId", path)
        assertEquals("form-token", token)
        assertEquals(mapper.readTree(fields), snapshot.questions)
        assertEquals(7L, snapshot.version)
        load("""{"101":"a","103":["x"],"104":true}""")
    }

    @Test
    fun `unknown missing wrongly typed conditional and invalid options are rejected`() {
        val invalid =
            listOf(
                """{"101":"a","103":["x"],"104":true,"999":"answer"}""",
                """{"101":"removed","103":["x"],"104":true}""",
                """{"101":"b","103":["x"],"104":true}""",
                """{"101":"a","102":"hidden","103":["x"],"104":true}""",
                """{"101":"a","103":["x","y"],"104":true}""",
                """{"101":"a","103":["x","x"],"104":true}""",
                """{"101":"a","103":["y"],"104":true}""",
                """{"101":"a","104":"true"}""",
                """{"101":"a","103":["x"]}""",
                """{"101":1,"103":["x"],"104":true}""",
            )
        invalid.forEach { assertEquals(400, assertThrows(ResponseStatusException::class.java) { load(it) }.statusCode.value()) }
    }

    @Test
    fun `changed form identity expo purpose and version cannot reach persistence`() {
        listOf(
            definition().replace(formId.toString(), UUID.randomUUID().toString()),
            definition().replace(expo, UUID.randomUUID().toString()),
            definition().replace("PROGRAM", "REGISTRATION"),
            definition().replace("\"version\":7", "\"version\":8"),
        ).forEach {
            response = it
            assertThrows(ProgramApplicationConflictException::class.java) { load() }
        }
    }

    @Test
    fun `malformed and unsupported definitions fail closed and supplier failures map safely`() {
        listOf(
            "{}",
            definition().replace("2100-01-01T00:00:00Z", "invalid"),
            definition().replace("\"requiredStatus\":true", "\"requiredStatus\":\"true\""),
            definition().replace("\"parentIndex\":0", "\"parentIndex\":10"),
            definition().replace("\"id\":102", "\"id\":101"),
        ).forEach {
            response = it
            assertEquals(502, assertThrows(ResponseStatusException::class.java) { load() }.statusCode.value())
        }
        response = definition().replace("SENTENCE", "IMAGE")
        assertEquals(503, assertThrows(ResponseStatusException::class.java) { load() }.statusCode.value())
        response = definition().replace("\"hasEtc\":false", "\"hasEtc\":true")
        assertEquals(503, assertThrows(ResponseStatusException::class.java) { load() }.statusCode.value())
        response = definition()
        mapOf(401 to 502, 404 to 404, 500 to 503).forEach { (upstream, expected) ->
            status = upstream
            assertEquals(expected, assertThrows(ResponseStatusException::class.java) { load() }.statusCode.value())
        }
    }

    private fun load(answers: String = """{"101":"a","103":["x"],"104":true}""") =
        adapter.loadAndValidate(expo, formId, 7, mapper.readTree(answers))

    private fun definition() =
        """{"id":"$formId","expoId":"$expo","purpose":"PROGRAM","version":7,"participantType":null,""" +
            """"startDate":"2020-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":$fields}"""
}
