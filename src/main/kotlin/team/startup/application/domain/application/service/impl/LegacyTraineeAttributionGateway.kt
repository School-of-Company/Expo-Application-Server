package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionRequest
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyTraineeAttributionGateway(
    private val expo: AdminExpoGateway,
    mapper: ObjectMapper,
    @Value("\${application.registration.user-url:}") url: String,
    @Value("\${application.registration.user-internal-token:}") token: String,
) {
    private val http = AdminSourceHttp(mapper, url, token)

    fun link(
        expoId: UUID,
        authorization: String?,
        request: LegacyTraineeAttributionRequest,
    ): LegacyTraineeAttributionResult = change(expoId, authorization, request, revoke = false)

    fun revoke(
        expoId: UUID,
        authorization: String?,
        request: LegacyTraineeAttributionRequest,
    ): LegacyTraineeAttributionResult = change(expoId, authorization, request, revoke = true)

    private fun change(
        expoId: UUID,
        authorization: String?,
        request: LegacyTraineeAttributionRequest,
        revoke: Boolean,
    ): LegacyTraineeAttributionResult {
        if (request.traineeId <= 0 || request.participantId <= 0 || request.reason.isBlank() || request.reason.length > 500) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "연수자·참가자 ID와 확인 근거(1~500자)가 필요합니다.")
        }
        val admin = expo.requireCancelAccess(expoId, authorization)
        return changeVerified(expoId, request, admin.adminId, revoke)
    }

    internal fun changeVerified(
        expoId: UUID,
        request: LegacyTraineeAttributionRequest,
        actorAdminId: Long,
        revoke: Boolean,
    ): LegacyTraineeAttributionResult {
        require(actorAdminId > 0)
        val tree =
            http.read(
                "/internal/trainees/participant-link" + if (revoke) "/revoke" else "",
                body =
                    mapOf(
                        "expoId" to expoId.toString(),
                        "traineeId" to request.traineeId,
                        "participantId" to request.participantId,
                        "actorAdminId" to actorAdminId,
                        "reason" to request.reason,
                    ),
                propagatedStatuses = setOf(400, 403, 404, 409),
            )
        return http.valid {
            require(tree.path("traineeId").isIntegralNumber && tree.path("traineeId").canConvertToLong())
            require(tree.path("traineeId").asLong() == request.traineeId && tree.path("changed").isBoolean)
            val participant = tree.get("participantId")
            if (revoke) {
                require(participant == null || participant.isNull)
            } else {
                require(participant != null && participant.isIntegralNumber && participant.canConvertToLong())
                require(participant.asLong() == request.participantId)
            }
            LegacyTraineeAttributionResult(request.traineeId, if (revoke) null else request.participantId, tree.path("changed").asBoolean())
        }
    }
}
