package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.SessionAttendanceMessage
import team.startup.application.domain.application.repository.SessionAttendanceOutboxRepository
import java.io.IOException

@Component
@ConditionalOnProperty(name = ["application.attendance.enabled"], havingValue = "true")
class SessionAttendanceDispatcher(
    private val repository: SessionAttendanceOutboxRepository,
    private val gateway: SessionAttendanceGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NEVER)
    fun dispatchNext(): Boolean = repository.deliverNext(::deliver)

    private fun deliver(
        repository: SessionAttendanceOutboxRepository,
        message: SessionAttendanceMessage,
    ): Boolean {
        val delivery = if (repository.lockExpoForDelivery(message.expoId)) message.purge() else message
        try {
            gateway.send(delivery)
            if (!delivery.delete && repository.lockExpoForDelivery(message.expoId)) {
                gateway.send(message.purge())
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            repository.retry(message, "Attendance delivery interrupted")
            return false
        } catch (ex: Exception) {
            val httpError = if (ex is IOException) ex.message?.takeIf { it.matches(Regex("Attendance HTTP [0-9]{3}")) } else null
            val error = httpError ?: if (ex is IOException) "Attendance connection failure" else "Attendance delivery failure"
            repository.retry(message, error)
            log.warn("Attendance delivery {} failed: {}", message.id, error)
            return httpError != null && !httpError.startsWith("Attendance HTTP 5")
        }
        repository.complete(message.id)
        return true
    }
}
