package team.startup.application.domain.application.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
@ConditionalOnProperty(name = ["application.attendance.enabled"], havingValue = "true")
class SessionAttendanceScheduler(
    private val dispatcher: SessionAttendanceDispatcher,
) {
    @Scheduled(
        fixedDelayString = "\${application.attendance.poll-delay-ms:1000}",
        initialDelayString = "\${application.attendance.poll-delay-ms:1000}",
    )
    fun dispatch() {
        repeat(100) {
            if (!dispatcher.dispatchNext()) return
        }
    }
}
