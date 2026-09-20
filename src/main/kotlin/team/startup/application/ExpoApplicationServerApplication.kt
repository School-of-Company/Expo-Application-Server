package team.startup.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExpoApplicationServerApplication

fun main(args: Array<String>) {
    runApplication<ExpoApplicationServerApplication>(*args)
}
