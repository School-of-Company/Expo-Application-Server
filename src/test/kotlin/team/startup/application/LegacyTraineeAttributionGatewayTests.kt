package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.validation.Validation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionRequest
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult
import team.startup.application.domain.application.service.impl.AdminExpoGateway
import team.startup.application.domain.application.service.impl.LegacyTraineeAttributionGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class LegacyTraineeAttributionGatewayTests {
    private val mapper = jacksonObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var gateway: LegacyTraineeAttributionGateway
    private lateinit var expo: AdminExpoGateway
    private lateinit var url: String
    private val calls = CopyOnWriteArrayList<Call>()

    @Volatile private var accessStatus = 200

    @Volatile private var accessBody = ACCESS

    @Volatile private var userStatus = 200

    @Volatile private var userBody = LINKED

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.add(
                Call(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("X-Internal-Token"),
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.bufferedReader().use { it.readText() },
                ),
            )
            val isExpo = exchange.requestURI.path.startsWith("/expo/")
            val code = if (isExpo) accessStatus else userStatus
            val bytes = (if (isExpo) accessBody else userBody).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (code != 204) it.write(bytes) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        expo = AdminExpoGateway(mapper, "$url/expo", "expo-token")
        gateway = LegacyTraineeAttributionGateway(expo, mapper, "$url/user/", "user-token")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `links explicit IDs with verified actor and original reason using only target tokens`() {
        assertEquals(LegacyTraineeAttributionResult(7, 9, true), gateway.link(EXPO, BEARER, REQUEST))
        assertEquals(2, calls.size)
        assertEquals(Call("GET", "/expo/internal/expo/$EXPO/admin-application-cancel-access", "expo-token", BEARER, ""), calls[0])
        val sent = calls[1]
        assertEquals("POST", sent.method)
        assertEquals("/user/internal/trainees/participant-link", sent.path)
        assertEquals("user-token", sent.token)
        assertEquals(null, sent.authorization)
        assertEquals(
            mapper.readTree("""{"expoId":"$EXPO","traineeId":7,"participantId":9,"actorAdminId":42,"reason":"${REQUEST.reason}"}"""),
            mapper.readTree(sent.body),
        )
    }

    @Test
    fun `unchanged link and revoke retain provider changed flag and revalidate admin on every call`() {
        userBody = LINKED.replace("true", "false")
        assertEquals(LegacyTraineeAttributionResult(7, 9, false), gateway.link(EXPO, BEARER, REQUEST))
        for (changed in listOf(true, false)) {
            userBody = """{"traineeId":7,"participantId":null,"changed":$changed}"""
            assertEquals(LegacyTraineeAttributionResult(7, null, changed), gateway.revoke(EXPO, BEARER, REQUEST))
            assertEquals("/user/internal/trainees/participant-link/revoke", calls.last().path)
        }
        assertEquals(3, calls.count { it.path.startsWith("/expo/") })
    }

    @Test
    fun `nonpositive IDs blank and oversized reasons fail before any remote call`() {
        val invalid =
            listOf(
                REQUEST.copy(traineeId = 0),
                REQUEST.copy(traineeId = -1),
                REQUEST.copy(participantId = 0),
                REQUEST.copy(participantId = -1),
                REQUEST.copy(reason = ""),
                REQUEST.copy(reason = " \n\t"),
                REQUEST.copy(reason = "x".repeat(501)),
            )
        Validation.buildDefaultValidatorFactory().use { factory ->
            invalid.forEach { request ->
                assertEquals(false, factory.validator.validate(request).isEmpty())
                for (revoke in listOf(false, true)) {
                    failure(HttpStatus.BAD_REQUEST) { change(revoke, request) }
                }
            }
            assertEquals(true, factory.validator.validate(REQUEST.copy(reason = "x".repeat(500))).isEmpty())
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun `missing or malformed bearer never reaches Expo or User`() {
        for (authorization in listOf(null, "", "Basic secret", "Bearer ")) {
            failure(HttpStatus.UNAUTHORIZED) { gateway.link(EXPO, authorization, REQUEST) }
            failure(HttpStatus.UNAUTHORIZED) { gateway.revoke(EXPO, authorization, REQUEST) }
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun `Expo denial and outage never call User`() {
        for ((code, expected) in listOf(
            401 to HttpStatus.UNAUTHORIZED,
            403 to HttpStatus.FORBIDDEN,
            404 to HttpStatus.NOT_FOUND,
            500 to HttpStatus.SERVICE_UNAVAILABLE,
            503 to HttpStatus.SERVICE_UNAVAILABLE,
        )) {
            accessStatus = code
            for (revoke in listOf(false, true)) failure(expected) { change(revoke) }
        }
        assertEquals(0, calls.count { it.path.startsWith("/user/") })
    }

    @Test
    fun `wrong expo actor authority or policy in access response never reaches User`() {
        for (body in listOf(
            ACCESS.replace(EXPO.toString(), UUID.randomUUID().toString()),
            ACCESS.replace("42", "0"),
            ACCESS.replace("42", "\"42\""),
            ACCESS.replace("APPLICATION_CANCEL", "APPLICATION_READ"),
            ACCESS.replace("EXPO_ADMIN", "GLOBAL_ADMIN"),
            "{}",
        )) {
            accessBody = body
            failure(HttpStatus.BAD_GATEWAY) { change(false) }
            failure(HttpStatus.BAD_GATEWAY) { change(true) }
        }
        assertEquals(0, calls.count { it.path.startsWith("/user/") })
    }

    @Test
    fun `User denials preserve request status without exposing provider details`() {
        userBody = "sensitive provider error"
        for (code in listOf(400, 403, 404, 409)) {
            userStatus = code
            for (revoke in listOf(false, true)) failure(HttpStatus.valueOf(code)) { change(revoke) }
        }
    }

    @Test
    fun `User internal auth unexpected statuses and outage cannot imply success`() {
        for ((code, expected) in listOf(
            401 to HttpStatus.BAD_GATEWAY,
            201 to HttpStatus.BAD_GATEWAY,
            204 to HttpStatus.BAD_GATEWAY,
            302 to HttpStatus.BAD_GATEWAY,
            500 to HttpStatus.SERVICE_UNAVAILABLE,
            503 to HttpStatus.SERVICE_UNAVAILABLE,
        )) {
            userStatus = code
            for (revoke in listOf(false, true)) failure(expected) { change(revoke) }
        }
    }

    @Test
    fun `malformed success mismatched IDs and missing or coerced changed are not accepted`() {
        val invalid =
            listOf(
                "{}",
                "[]",
                "null",
                "{",
                LINKED.replace("\"traineeId\":7", "\"traineeId\":8"),
                LINKED.replace("\"traineeId\":7", "\"traineeId\":\"7\""),
                LINKED.replace("\"traineeId\":7", "\"traineeId\":9223372036854775808"),
                LINKED.replace(",\"changed\":true", ""),
                LINKED.replace("true", "\"true\""),
                LINKED.replace("true", "1"),
                LINKED.replace("true", "null"),
            )
        for (body in invalid) {
            userBody = body
            for (revoke in listOf(false, true)) failure(HttpStatus.BAD_GATEWAY) { change(revoke) }
        }
        for (participant in listOf("null", "8", "\"9\"", "9223372036854775808")) {
            userBody = LINKED.replace("\"participantId\":9", "\"participantId\":$participant")
            failure(HttpStatus.BAD_GATEWAY) { change(false) }
        }
        userBody = LINKED
        failure(HttpStatus.BAD_GATEWAY) { change(true) }
    }

    @Test
    fun `missing target configuration and network failures never fabricate success`() {
        for ((targetUrl, token) in listOf("" to "user-token", "$url/user" to "")) {
            val unconfigured = LegacyTraineeAttributionGateway(expo, mapper, targetUrl, token)
            failure(HttpStatus.SERVICE_UNAVAILABLE) { unconfigured.link(EXPO, BEARER, REQUEST) }
            failure(HttpStatus.SERVICE_UNAVAILABLE) { unconfigured.revoke(EXPO, BEARER, REQUEST) }
        }
        assertEquals(0, calls.count { it.path.startsWith("/user/") })
        val unavailable = LegacyTraineeAttributionGateway(expo, mapper, "http://127.0.0.1:1", "user-token")
        failure(HttpStatus.SERVICE_UNAVAILABLE) { unavailable.link(EXPO, BEARER, REQUEST) }
        failure(HttpStatus.SERVICE_UNAVAILABLE) { unavailable.revoke(EXPO, BEARER, REQUEST) }
    }

    @Test
    fun `ambiguous success does not automatically retry or revoke and explicit retry preserves the pair`() {
        userBody = "{"
        failure(HttpStatus.BAD_GATEWAY) { change(false) }
        assertEquals(1, calls.count { it.path.startsWith("/user/") })
        val original = calls.last()
        userBody = LINKED.replace("true", "false")
        assertEquals(LegacyTraineeAttributionResult(7, 9, false), change(false))
        assertEquals(2, calls.count { it.path.startsWith("/user/") })
        assertEquals(original, calls.last())
    }

    private fun change(
        revoke: Boolean,
        request: LegacyTraineeAttributionRequest = REQUEST,
    ): LegacyTraineeAttributionResult = if (revoke) gateway.revoke(EXPO, BEARER, request) else gateway.link(EXPO, BEARER, request)

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        val error = assertThrows(ResponseStatusException::class.java, action)
        assertEquals(expected, error.statusCode)
        assertEquals(false, error.message.orEmpty().contains("sensitive provider error"))
    }

    private data class Call(
        val method: String,
        val path: String,
        val token: String?,
        val authorization: String?,
        val body: String,
    )

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000136")
        private const val BEARER = "Bearer admin-access-token"
        private val REQUEST = LegacyTraineeAttributionRequest(7, 9, "운영자가 확인한 자료와 확인 방법")
        private const val LINKED = """{"traineeId":7,"participantId":9,"changed":true}"""
        private val ACCESS =
            """{"expoId":"$EXPO","adminId":42,"permission":"APPLICATION_CANCEL","policyVersion":"EXPO_ADMIN_APPLICATION_CANCEL_V1","scope":"EXPO_ADMIN"}"""
    }
}
