package team.startup.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.cloud.openfeign.EnableFeignClients
import team.startup.application.global.client.attendance.AttendanceClient
import team.startup.application.global.client.attendance.AttendanceClientProperties

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@EnableFeignClients(clients = [AttendanceClient::class])
@EnableConfigurationProperties(AttendanceClientProperties::class)
class ExpoApplicationServerApplication

fun main(args: Array<String>) {
    runApplication<ExpoApplicationServerApplication>(*args)
}
