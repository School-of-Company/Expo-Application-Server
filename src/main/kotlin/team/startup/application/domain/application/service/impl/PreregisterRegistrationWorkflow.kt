package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.RegistrationCommand
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PreregisterRegistrationWorkflow(
    private val reservations: PreregisterReservationRepository,
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val gateway: RegistrationGateway,
    private val attendance: SessionAttendanceOutbox,
    private val mapper: ObjectMapper,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
) {
    private fun fingerprint(command: RegistrationCommand): String =
        mapper.writeValueAsString(command.copy(expoId = command.expoId.lowercase(), idempotencyKey = null))

    fun resume(command: RegistrationCommand): Boolean {
        val expoId = UUID.fromString(command.expoId)
        val fingerprint = fingerprint(command)
        val saved =
            ledger.transactions.execute {
                ledger.lock(expoId)
                reservations.find(expoId, command.idempotencyKey, fingerprint)?.also {
                    if (it.command != fingerprint) conflict("같은 신청 키의 내용이 다릅니다.")
                }
            } ?: return false
        recover(expoId, saved.requestId)
        return true
    }

    fun register(
        command: RegistrationCommand,
        request: ParticipantRegistration,
        applicants: Int,
    ) {
        if (!attendanceEnabled) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "참여 서비스 회차 통지를 활성화해야 합니다.",
            )
        }
        val expoId = UUID.fromString(request.expoId)
        val sessionId = command.request.sessionId ?: badSessionRequest("사전등록 회차 ID가 필요합니다.")
        val observation = ledger.observe(expoId, sessionId)
        val fingerprint = fingerprint(command)
        val key = command.idempotencyKey ?: UUID.randomUUID().toString()
        val reserved =
            requireNotNull(
                ledger.transactions.execute {
                    ledger.lock(expoId)
                    reservations.find(expoId, command.idempotencyKey, fingerprint)?.let {
                        if (it.command != fingerprint) conflict("같은 신청 키의 내용이 다릅니다.")
                        return@execute it.requestId
                    }
                    val definition = ledger.current(expoId, sessionId, observation)
                    if (definition.closed) conflict("마감된 회차입니다.")
                    if (applicants !in 1..5) {
                        badSessionRequest("대표자를 포함해 최대 5명까지 신청할 수 있습니다.")
                    }
                    val phone = request.phoneNumber.filter { it in '0'..'9' }
                    if (phone.isEmpty()) badSessionRequest("전화번호가 올바르지 않습니다.")
                    if (reservations.samePhoneInUse(expoId, phone)) {
                        conflict("대표자의 활성 신청 또는 복구 중인 신청이 있습니다.")
                    }
                    if (definition.capacity.toLong() - repository.occupied(expoId, sessionId) < applicants) {
                        conflict("일행 전원을 수용할 수 없습니다.")
                    }
                    // User requestId is global across expos and participant types; reserve a separate, stable UUID.
                    reservations.insert(
                        expoId,
                        key,
                        sessionId,
                        fingerprint,
                        request.copy(requestId = UUID.randomUUID().toString()),
                        applicants,
                    )
                    key
                },
            )
        recover(expoId, reserved)
    }

    fun recover(
        expoId: UUID,
        requestId: String,
    ) {
        if (!attendanceEnabled) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "참여 서비스 회차 통지를 활성화해야 합니다.",
            )
        }
        val reservation =
            requireNotNull(
                ledger.transactions.execute {
                    repository.lock(expoId)
                    val saved =
                        reservations.find(expoId, requestId, "") ?: throw ResponseStatusException(
                            if (repository.expoDeleted(expoId)) HttpStatus.CONFLICT else HttpStatus.NOT_FOUND,
                            "복구할 신청이 없습니다.",
                        )
                    if (saved.state != "RESERVED") return@execute saved
                    if (repository.expoDeleted(expoId) && saved.attempts == 0) {
                        reservations.finish(saved, 409)
                        saved.copy(state = "REJECTED", errorStatus = 409)
                    } else {
                        // Persist attempted delivery before HTTP, even if the process dies after User commits.
                        if (saved.errorStatus == null || repository.expoDeleted(expoId)) reservations.retry(saved)
                        saved
                    }
                },
            )
        if (reservation.state == "COMPLETED") return
        if (reservation.state == "REJECTED") {
            throw ResponseStatusException(
                HttpStatus.valueOf(requireNotNull(reservation.errorStatus)),
                "참가자 저장이 거절되었습니다.",
            )
        }
        if (reservation.errorStatus != null && !repository.expoDeleted(expoId)) {
            throw ResponseStatusException(HttpStatus.valueOf(reservation.errorStatus), "User 계약 확인과 운영자 복구가 필요합니다. 좌석을 유지합니다.")
        }
        // Concurrent deliveries use the same snapshot and User requestId; only one local completion can commit.
        val result =
            reservation.result ?: try {
                gateway.participant("STANDARD", reservation.userRequest)
            } catch (ex: ResponseStatusException) {
                val completed =
                    ledger.transactions.execute {
                        repository.lock(expoId)
                        val current = reservations.find(expoId, requestId, "") ?: return@execute false
                        if (current.state == "COMPLETED") return@execute true
                        if (current.state == "RESERVED" && current.errorStatus == null) {
                            if (ex is ParticipantSaveRejectedException) {
                                reservations.finish(current, ex.statusCode.value())
                            } else if (ex is ParticipantIdsMismatchException) {
                                reservations.needsReview(current, ex.statusCode.value())
                            } else {
                                reservations.retry(current)
                            }
                        }
                        false
                    }
                if (completed == true) return
                throw ex
            }
        val failure =
            ledger.transactions.execute {
                // Serialize completion, cancellation and purge after the HTTP call.
                repository.lock(expoId)
                val reserved =
                    reservations.find(expoId, requestId, "") ?: return@execute ResponseStatusException(
                        if (repository.expoDeleted(expoId)) HttpStatus.CONFLICT else HttpStatus.NOT_FOUND,
                        "복구할 신청이 없습니다.",
                    )
                if (reserved.state == "COMPLETED") return@execute null
                if (reserved.state == "REJECTED") {
                    return@execute ResponseStatusException(
                        HttpStatus.valueOf(requireNotNull(reserved.errorStatus)),
                        "참가자 저장이 거절되었습니다.",
                    )
                }
                reservations.result(reserved, result)
                val ids = result.groupParticipantIds
                if (repository.expoDeleted(expoId)) {
                    ids.forEach { attendance.cancelled(expoId, it) }
                    reservations.finish(reserved, 409)
                    return@execute ResponseStatusException(HttpStatus.CONFLICT, "삭제된 박람회입니다.")
                }
                if (ids.size != reserved.applicants || result.participantId !in ids ||
                    ids.any { it <= 0 } || ids.distinct().size != ids.size
                ) {
                    reservations.needsReview(reserved, 502)
                    return@execute ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "User 참가자 목록과 선점 인원이 일치하지 않습니다. 좌석을 유지합니다.",
                    )
                }
                if (ids.any { repository.active(expoId, it) != null }) {
                    reservations.needsReview(reserved, 409)
                    return@execute ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "이미 활성 신청이 있는 참가자입니다. 좌석을 유지합니다.",
                    )
                }
                ids.forEach {
                    repository.insert(expoId, reserved.sessionId, result.participantId, it, "CONFIRMED", reserved.requestId)
                    attendance.confirmed(expoId, it, reserved.sessionId)
                }
                reservations.enqueueCounts(expoId, ids)
                reservations.finish(reserved)
                null
            }
        if (failure != null) throw failure
    }
}
