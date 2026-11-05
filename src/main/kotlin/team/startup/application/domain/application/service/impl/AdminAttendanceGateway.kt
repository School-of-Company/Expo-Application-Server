package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationSessionSummary
import team.startup.application.domain.application.presentation.dto.AdminProgramAttendanceSummary
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.global.client.attendance.AttendanceClient
import team.startup.application.global.client.attendance.AttendanceClientProperties
import team.startup.application.global.client.callService
import tools.jackson.databind.ObjectMapper

@Component
class AdminAttendanceGateway(
    private val mapper: ObjectMapper,
    private val client: AttendanceClient,
    private val properties: AttendanceClientProperties,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun summarize(sessions: List<AdminApplicationSessionSummary>): Map<Pair<ProgramSessionType, Long>, AdminProgramAttendanceSummary> {
        if (sessions.isEmpty()) return emptyMap()
        if (!properties.configured) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 조회 연동이 설정되지 않았습니다.")
        }
        val refs = sessions.map { AttendanceClient.ProgramRef(it.programType, it.programId) }.distinct()
        val tree = callService(::invalidResponse, ::failedResponse) { client.summarize(AttendanceClient.SummaryRequest(refs)) }
        return try {
            require(tree.isArray && tree.all { it["programId"]?.isIntegralNumber == true && it["attendeeCount"]?.isIntegralNumber == true })
            val rows = mapper.treeToValue(tree, Array<AdminProgramAttendanceSummary>::class.java).toList()
            require(rows.map { AttendanceClient.ProgramRef(it.programType, it.programId) } == refs)
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
        if (!properties.configured) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 조회 연동이 설정되지 않았습니다.")
        }
        val response = callService(::invalidResponse, ::failedResponse) { client.attendances(type.name.lowercase(), programId) }
        val field = if (type == ProgramSessionType.TRAINING) "traineeId" else "participantId"
        val tree =
            try {
                response.also { tree ->
                    require(tree.isArray && tree.all { it[field]?.isIntegralNumber == true && it[field].canConvertToLong() })
                }
            } catch (exception: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답이 올바르지 않습니다.", exception)
            }
        return tree.any { it[field].asLong() == personId }
    }

    private fun invalidResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답이 올바르지 않습니다.")

    private fun failedResponse(status: Int): Nothing =
        when (status) {
            -1 -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 원천을 조회할 수 없습니다.")
            in 500..599 -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 원천이 응답하지 않습니다.")
            else -> throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 원천 응답 상태가 올바르지 않습니다.")
        }

    private companion object {
        val TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    }
}
