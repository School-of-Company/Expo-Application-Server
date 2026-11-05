package team.startup.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class ExpoApplicationServerApplication

fun main(args: Array<String>) {
    runApplication<ExpoApplicationServerApplication>(*args)
}
