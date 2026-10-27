package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.repository.ExpoProjectionRepository
import team.startup.application.domain.application.repository.ExpoProjectionStore
import team.startup.application.domain.application.service.impl.AdminGroupMessageGateway
import team.startup.application.domain.application.service.impl.ExpoChangedConsumer
import team.startup.application.domain.application.service.impl.ExpoPeriod
import team.startup.application.domain.application.service.impl.ExpoProjection
import team.startup.application.domain.application.service.impl.OwnerCancellationDeadlineGateway
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

// KAFKA_EXPO_PROJECTION_ENABLED 꺼짐(projection 없음)·켜짐 적중·MISS 동기 대체를 Expo 모의 서버 호출로 확인한다.
class ExpoProjectionReadTests {
    private lateinit var server: HttpServer
    private val paths = CopyOnWriteArrayList<String>()
    private val mapper = jacksonObjectMapper()

    private val store =
        object : ExpoProjectionStore {
            val expos = mutableMapOf<UUID, ExpoProjectionRepository.Expo>()
            val sessions = mutableMapOf<Pair<UUID, Long>, ExpoProjectionRepository.Session>()

            override fun expo(expoId: UUID) = expos[expoId]

            override fun session(
                expoId: UUID,
                sessionId: Long,
            ) = sessions[expoId to sessionId]
        }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            paths.add(exchange.requestURI.path)
            val body =
                if (exchange.requestURI.path.endsWith("/preregister-sessions/7")) {
                    """{"id":7,"expoId":"$EXPO","cancelEndedAt":"2026-10-12T10:00:00+09:00"}"""
                } else {
                    """{"title":" 원격 박람회 ","startedDay":"2026-10-01","finishedDay":"2026-10-03",""" +
                        """"participantPhoneVerificationRequired":false,"participantPhoneVerificationRevision":0}"""
                }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private val url get() = "http://127.0.0.1:${server.address.port}"

    private fun registration(projection: ExpoProjection?) =
        RegistrationGateway(
            mapper = mapper,
            expoUrl = url,
            formUrl = "",
            userUrl = "",
            expoInternalToken = "expo-token",
            formInternalToken = "",
            userInternalToken = "",
            projection = projection,
        )

    private fun groupMessage(projection: ExpoProjection?) =
        AdminGroupMessageGateway(mapper, url, "", "", "expo-token", "", "", projection = projection)

    private fun deadlines(projection: ExpoProjection?) =
        OwnerCancellationDeadlineGateway(mapper, url, "expo-token", projection = projection)

    private val remotePeriod = ExpoPeriod("2026-10-01", "2026-10-03", false, 0)

    @Test
    fun `flag off keeps every read on the synchronous Expo call`() {
        store.expos[EXPO] = LOCAL
        store.sessions[EXPO to 7L] = ExpoProjectionRepository.Session(7, Instant.parse("2030-01-01T00:00:00Z"))

        assertEquals(remotePeriod, registration(null).expoPeriod(EXPO.toString()))
        assertEquals("원격 박람회", groupMessage(null).expoName(EXPO))
        assertEquals(Instant.parse("2026-10-12T01:00:00Z"), deadlines(null).preregister(EXPO, 7))
        assertEquals(listOf("/internal/expo/$EXPO", "/internal/expo/$EXPO", "/internal/expo/$EXPO/preregister-sessions/7"), paths)
    }

    @Test
    fun `flag on reads the projection without calling Expo`() {
        store.expos[EXPO] = LOCAL
        store.sessions[EXPO to 7L] = ExpoProjectionRepository.Session(7, Instant.parse("2030-01-01T00:00:00Z"))
        val projection = ExpoProjection(store)

        assertEquals(ExpoPeriod("2030-01-01", "2030-01-02", true, 3), registration(projection).expoPeriod(EXPO.toString()))
        assertEquals(ExpoPeriod("2030-01-01", "2030-01-02", true, 3), registration(projection).expoPeriod(EXPO.toString().uppercase()))
        assertEquals("로컬 박람회", groupMessage(projection).expoName(EXPO))
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), deadlines(projection).preregister(EXPO, 7))
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun `projected missing deadline is rejected like the synchronous response`() {
        store.expos[EXPO] = LOCAL
        store.sessions[EXPO to 7L] = ExpoProjectionRepository.Session(7, null)

        val failure = assertThrows(ResponseStatusException::class.java) { deadlines(ExpoProjection(store)).preregister(EXPO, 7) }

        assertEquals(HttpStatus.CONFLICT, failure.statusCode)
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun `flag on with a projection miss falls back to the synchronous call`() {
        val projection = ExpoProjection(store)

        assertEquals(remotePeriod, registration(projection).expoPeriod(EXPO.toString()))
        assertEquals(remotePeriod, registration(projection).expoPeriod("not-a-uuid").also { paths.removeAt(paths.lastIndex) })
        assertEquals("원격 박람회", groupMessage(projection).expoName(EXPO))
        assertEquals(Instant.parse("2026-10-12T01:00:00Z"), deadlines(projection).preregister(EXPO, 7))
        assertEquals(listOf("/internal/expo/$EXPO", "/internal/expo/$EXPO", "/internal/expo/$EXPO/preregister-sessions/7"), paths)
    }

    @Test
    fun `parser accepts the Expo contract and ignores unknown fields`() {
        val change = ExpoChangedConsumer.parse(mapper, EXPO.toString(), event())

        assertEquals(EXPO, change.expoId)
        assertEquals(1042L, change.version)
        assertEquals(LOCAL.copy(title = "예시 박람회"), change.expo)
        assertEquals(listOf(ExpoProjectionRepository.Session(7, Instant.parse("2030-01-01T01:30:00Z"))), change.sessions)

        val upper = EXPO.toString().uppercase()
        assertEquals(EXPO, ExpoChangedConsumer.parse(mapper, upper, event().replace(EXPO.toString(), upper)).expoId)

        val tombstone =
            ExpoChangedConsumer.parse(
                mapper,
                EXPO.toString(),
                """{"schemaVersion":1,"eventId":"$EVENT","version":1043,"occurredAt":"x","expoId":"$EXPO","deleted":true}""",
            )
        assertEquals(null, tombstone.expo)
        assertEquals(emptyList<ExpoProjectionRepository.Session>(), tombstone.sessions)
    }

    @Test
    fun `parser rejects messages it cannot apply safely`() {
        for ((key, value) in listOf(
            EXPO.toString() to null,
            EXPO.toString() to "[]",
            EXPO.toString() to "{",
            UUID.randomUUID().toString() to event(),
            null to event(),
            EXPO.toString() to event().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            EXPO.toString() to event().replace("\"version\":1042", "\"version\":0"),
            EXPO.toString() to event().replace("\"version\":1042", "\"version\":\"1042\""),
            EXPO.toString() to event().replace(EVENT.toString(), "not-a-uuid"),
            EXPO.toString() to event().replace("\"deleted\":false", "\"deleted\":\"false\""),
            EXPO.toString() to event().replace("\"title\":\"예시 박람회\",", ""),
            EXPO.toString() to event().replace("\"participantPhoneVerificationRevision\":3", "\"participantPhoneVerificationRevision\":-1"),
            EXPO.toString() to event().replace("\"deleting\":false", "\"deleting\":null"),
            EXPO.toString() to event().replace("\"id\":7", "\"id\":0"),
            EXPO.toString() to event().replace("2030-01-01T01:30:00Z", "2030-01-01T10:30:00"),
            EXPO.toString() to event().replace("\"cancelEndedAt\":\"2030-01-01T01:30:00Z\"", "\"cancelEndedAt\":1"),
            EXPO.toString() to event().replace("\"preregisterSessions\":[", "\"preregisterSessions\":[{\"id\":7,\"cancelEndedAt\":null},"),
        )) {
            assertThrows(Exception::class.java, { ExpoChangedConsumer.parse(mapper, key, value) }, value ?: "null")
        }
    }

    companion object {
        private val EXPO = UUID.fromString("0192f0c8-0000-7000-8000-000000000001")
        private val EVENT = UUID.fromString("6f1c3a0e-8a43-4d4e-9a55-0d6f3e1b2c11")
        private val LOCAL = ExpoProjectionRepository.Expo("로컬 박람회", "2030-01-01", "2030-01-02", true, 3, false)

        fun event(
            version: Long = 1042,
            title: String = "예시 박람회",
            cancelEndedAt: String? = "2030-01-01T01:30:00Z",
            deleting: Boolean = false,
            expoId: UUID = EXPO,
            eventId: UUID = EVENT,
        ) = """{"schemaVersion":1,"eventId":"$eventId","version":$version,"occurredAt":"2030-01-01T00:00:00.123456Z",""" +
            """"expoId":"$expoId","deleted":false,"expo":{"title":"$title","description":"설명","startedDay":"2030-01-01",""" +
            """"finishedDay":"2030-01-02","location":"광주","coverImage":null,"x":"1","y":"2","preregisterTeacherCapacity":null,""" +
            """"preregisterNonTeacherCapacity":null,"participantPhoneVerificationRequired":true,""" +
            """"participantPhoneVerificationRevision":3,"deleting":$deleting,"futureField":1},""" +
            """"standardPrograms":[],"trainingPrograms":[],"preregisterSessions":[{"id":7,"title":"1회차",""" +
            """"startedAt":"2030-01-01T01:00:00Z","endedAt":"2030-01-01T02:00:00Z","place":"강당","capacity":30,""" +
            """"waitingCapacity":5,"closed":false,"revision":1,"cancelEndedAt":${cancelEndedAt?.let { "\"$it\"" } ?: "null"},""" +
            """"teacherCapacity":null,"nonTeacherCapacity":null}]}"""
    }
}
