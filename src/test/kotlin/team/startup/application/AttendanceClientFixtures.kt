package team.startup.application

import feign.Feign
import org.springframework.cloud.openfeign.support.SpringMvcContract
import team.startup.application.domain.application.service.impl.AdminAttendanceGateway
import team.startup.application.domain.application.service.impl.AttendanceCancellationStatusGateway
import team.startup.application.domain.application.service.impl.DownstreamCircuits
import team.startup.application.global.client.attendance.AttendanceClient
import team.startup.application.global.client.attendance.AttendanceClientConfiguration
import team.startup.application.global.client.attendance.AttendanceClientProperties
import tools.jackson.databind.ObjectMapper

fun attendanceClient(
    mapper: ObjectMapper,
    properties: AttendanceClientProperties,
    circuits: DownstreamCircuits = DownstreamCircuits(),
): AttendanceClient {
    val config = AttendanceClientConfiguration()
    return Feign
        .builder()
        .contract(SpringMvcContract())
        .client(config.attendanceTransport(circuits))
        .encoder(config.attendanceEncoder(mapper))
        .decoder(config.attendanceDecoder(mapper))
        .requestInterceptor(config.attendanceInternalToken(properties))
        .options(config.attendanceOptions())
        .retryer(config.attendanceRetryer())
        .logLevel(config.attendanceLogLevel())
        .target(AttendanceClient::class.java, properties.url.ifBlank { "http://attendance.invalid" }.trimEnd('/'))
}

fun adminAttendanceGateway(
    mapper: ObjectMapper,
    url: String,
    token: String,
): AdminAttendanceGateway {
    val properties = AttendanceClientProperties(url, token)
    return AdminAttendanceGateway(mapper, attendanceClient(mapper, properties), properties)
}

fun attendanceCancellationStatusGateway(
    mapper: ObjectMapper,
    url: String,
    token: String,
): AttendanceCancellationStatusGateway {
    val properties = AttendanceClientProperties(url, token)
    return AttendanceCancellationStatusGateway(attendanceClient(mapper, properties), properties)
}
