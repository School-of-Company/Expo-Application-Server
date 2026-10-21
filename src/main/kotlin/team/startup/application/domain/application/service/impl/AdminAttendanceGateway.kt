package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationSessionSummary
import team.startup.application.domain.application.presentation.dto.AdminProgramAttendanceSummary
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class AdminAttendanceGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.attendance.url:}") private val url: String,
    @Value("\${application.attendance.internal-token:}") private val token: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun summarize(sessions: List<AdminApplicationSessionSummary>): Map<Pair<ProgramSessionType, Long>, AdminProgramAttendanceSummary> {
        if (sessions.isEmpty()) return emptyMap()
        if (url.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 조회 연동이 설정되지 않았습니다.")
        }
        val refs = sessions.map { ProgramRef(it.programType, it.programId) }.distinct()
        val response =
            try {
                client.send(
                    HttpRequest
                        .newBuilder(URI.create("${url.trimEnd('/')}/internal/program-attendances/summary"))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(SummaryRequest(refs))))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 원천을 조회할 수 없습니다.", exception)
            }
        if (response.statusCode() in 500..599) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 원천이 응답하지 않습니다.")
        }
        if (response.statusCode() != 200) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답 상태가 올바르지 않습니다.")
        }
        return try {
            val tree = mapper.readTree(response.body())
            require(tree.isArray && tree.all { it["programId"]?.isIntegralNumber == true && it["attendeeCount"]?.isIntegralNumber == true })
            val rows = mapper.readValue(response.body(), Array<AdminProgramAttendanceSummary>::class.java).toList()
            require(rows.map { ProgramRef(it.programType, it.programId) } == refs)
            require(
                rows.all { row ->
                    row.attendeeCount >= 0 &&
                        if (row.attendeeCount == 0L) {
                            row.firstEntryTime == null && row.lastEntryTime == null
                        } else {
                            row.firstEntryTime?.matches(TIME) == true && row.lastEntryTime?.matches(TIME) == true &&
                                row.firstEntryTime <= row.lastEntryTime
                        }
                },
            )
            rows.associateBy { it.programType to it.programId }
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답이 올바르지 않습니다.", exception)
        }
    }

    /** #261: 이 사람(일반은 참가자, 연수는 연수자)이 그 프로그램에 출석했는지. 관리자 프로그램 취소에 쓴다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun attended(
        type: ProgramSessionType,
        programId: Long,
        personId: Long,
    ): Boolean {
        if (url.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 조회 연동이 설정되지 않았습니다.")
        }
        val response =
            try {
                client.send(
                    HttpRequest
                        .newBuilder(URI.create("${url.trimEnd('/')}/internal/program-attendances/${type.name.lowercase()}/$programId"))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 원천을 조회할 수 없습니다.", exception)
            }
        if (response.statusCode() in 500..599) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 원천이 응답하지 않습니다.")
        }
        if (response.statusCode() != 200) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답 상태가 올바르지 않습니다.")
        }
        val field = if (type == ProgramSessionType.TRAINING) "traineeId" else "participantId"
        val tree =
            try {
                mapper.readTree(response.body()).also { tree ->
                    require(tree.isArray && tree.all { it[field]?.isIntegralNumber == true && it[field].canConvertToLong() })
                }
            } catch (exception: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답이 올바르지 않습니다.", exception)
            }
        return tree.any { it[field].asLong() == personId }
    }

    private data class ProgramRef(
        val programType: ProgramSessionType,
        val programId: Long,
    )

    private data class SummaryRequest(
        val programs: List<ProgramRef>,
    )

    private companion object {
        val TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    }
}
