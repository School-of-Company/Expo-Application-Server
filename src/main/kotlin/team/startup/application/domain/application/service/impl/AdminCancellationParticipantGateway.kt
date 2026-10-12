package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminCancellationParticipantStatus
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NEVER)
class AdminCancellationParticipantGateway(
    mapper: ObjectMapper,
    @Value("\${application.registration.user-url:}") url: String,
    @Value("\${application.registration.user-internal-token:}") token: String,
) {
    private val http = AdminSourceHttp(mapper, url, token)

    fun status(
        expoId: UUID,
        participantId: Long,
    ): AdminCancellationParticipantStatus {
        val tree =
            http.read(
                "/internal/standard-participants/admin-cancellation-status",
                mapOf(
                    "expoId" to expoId,
                    "participantId" to participantId,
                ),
                propagatedStatuses = setOf(404),
            )
        return http.valid {
            val id = tree.path("participantId")
            val entered = tree.path("entered")
            val linked = tree.path("traineeLinked")
            val trainee = tree.path("linkedTraineeId")
            require(id.isIntegralNumber && id.canConvertToLong() && id.asLong() == participantId)
            require(entered.isBoolean && linked.isBoolean && !trainee.isMissingNode)
            require(trainee.isNull || (trainee.isIntegralNumber && trainee.canConvertToLong() && trainee.asLong() > 0))
            require(linked.asBoolean() != trainee.isNull)
            AdminCancellationParticipantStatus(
                participantId,
                entered.asBoolean(),
                linked.asBoolean(),
                if (trainee.isNull) null else trainee.asLong(),
            )
        }
    }
}
