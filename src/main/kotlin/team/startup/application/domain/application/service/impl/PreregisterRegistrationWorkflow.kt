package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.PreregisterRegistrationReceipt
import team.startup.application.domain.application.presentation.dto.targeted
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.RegistrationCommand
import tools.jackson.databind.JsonNode
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
    private val waitlist: PreregisterWaitlist,
    private val companions: PreregisterCompanionPolicy,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
    @Value("\${application.registration.waitlist-intake-enabled:false}") private val waitlistIntake: Boolean,
    private val retryLimit: ReservationRetryLimit,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // #222: 확정되지 않은 실패가 상한까지 이어지면 좌석을 유지한 채 운영자 확인으로 넘기고, 그 순간 한 번만 경고한다.
    // needsReview 뒤에는 errorStatus가 있어 이 경로에 다시 오지 않으므로 경고가 되풀이되지 않는다.
    private fun retryOrReview(current: PreregisterReservation) {
        if (retryLimit.exceeded(current)) {
            reservations.needsReview(current, 502)
            log.warn("Preregister reservation moved to review after retry limit: expoId={} requestId={}", current.expoId, current.requestId)
        } else {
            reservations.retry(current)
        }
    }

    private fun fingerprint(command: RegistrationCommand): String =
        mapper.writeValueAsString(command.copy(expoId = command.expoId.lowercase(), idempotencyKey = null))

    fun resume(command: RegistrationCommand): PreregisterRegistrationReceipt? {
        val expoId = UUID.fromString(command.expoId)
        val fingerprint = fingerprint(command)
        val saved =
            ledger.transactions.execute {
                ledger.lock(expoId)
                reservations.find(expoId, command.idempotencyKey, fingerprint)?.also {
                    if (it.command != fingerprint) conflict("같은 신청 키의 내용이 다릅니다.")
                }
            } ?: return null
        return deliver(expoId, saved.requestId)
    }

    // A promoted party was already accepted, so a pending User delivery is reported as PROCESSING rather than an error.
    private fun deliver(
        expoId: UUID,
        requestId: String,
    ): PreregisterRegistrationReceipt {
        try {
            recover(expoId, requestId)
        } catch (ex: ResponseStatusException) {
            if (ex is WaitlistWithdrawnException) throw ex
            val current = ledger.transactions.execute { reservations.find(expoId, requestId, "") }
            if (current?.state == "RESERVED" &&
                current.queueSeq != null
            ) {
                return PreregisterRegistrationReceipt("PROCESSING", current.sessionId, null)
            }
            throw ex
        }
        return requireNotNull(
            ledger.transactions.execute {
                // A completed reservation can be purged right after delivery; that is still the confirmed 201.
                val saved = reservations.find(expoId, requestId, "") ?: return@execute PreregisterRegistrationReceipt("CONFIRMED", 0, null)
                when {
                    saved.state == "COMPLETED" -> PreregisterRegistrationReceipt("CONFIRMED", saved.sessionId, null)
                    saved.state == "WAITING" -> PreregisterRegistrationReceipt("WAITING", saved.sessionId, reservations.position(saved))
                    saved.state == "RESERVED" -> PreregisterRegistrationReceipt("PROCESSING", saved.sessionId, null)
                    saved.withdrawn -> throw WaitlistWithdrawnException()
                    else -> conflict("신청 상태가 변경되었습니다.")
                }
            },
        )
    }

    fun register(
        command: RegistrationCommand,
        request: ParticipantRegistration,
        applicants: Int,
    ): PreregisterRegistrationReceipt {
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
                    // #198: User returns the whole existing party for a known phone, so a second session cannot be matched to
                    // this submission. Further sessions of an existing party take participant IDs instead (#199).
                    if (reservations.samePhoneInUse(expoId, phone, waitlist.now())) {
                        conflict("대표자의 활성 신청 또는 복구 중인 신청이 있습니다.")
                    }
                    // User requestId is global across expos and participant types; reserve a separate, stable UUID.
                    val snapshot = request.copy(requestId = UUID.randomUUID().toString())
                    val queued = reservations.waitingApplicants(expoId, sessionId)
                    // New parties never overtake a waiting party, even when confirmed seats are free (#99 P3).
                    val categories = PreregisterSeatCategory.counts(snapshot, mapper)
                    if (definition.targeted) {
                        if (categories.first + categories.second != applicants) {
                            badSessionRequest("대상별 정원 회차는 대표자 직업 답변이 필요합니다. 사전등록 폼의 직업 문항을 확인해 주세요.")
                        }
                        val (teacher, other) = PreregisterSeatCategory.remaining(definition, repository.seatUsage(expoId, sessionId))
                        // Target sessions take no waiting party; the whole party fits both categories or nothing is reserved (#173).
                        if (queued > 0 || teacher < categories.first || other < categories.second ||
                            definition.capacity.toLong() - repository.occupied(expoId, sessionId) < applicants
                        ) {
                            conflict("일행 전원을 수용할 수 없습니다.")
                        }
                        reservations.insert(expoId, key, sessionId, fingerprint, snapshot, applicants, categories)
                    } else if (queued == 0L && definition.capacity.toLong() - repository.occupied(expoId, sessionId) >= applicants) {
                        reservations.insert(expoId, key, sessionId, fingerprint, snapshot, applicants, categories)
                    } else if (waitlistIntake && waitlist.accepts(definition) && (definition.waitingCapacity ?: 0) - queued >= applicants) {
                        reservations.insertWaiting(expoId, key, sessionId, fingerprint, snapshot, applicants, categories)
                    } else {
                        conflict("일행 전원을 수용할 수 없습니다.")
                    }
                    key
                },
            )
        return deliver(expoId, reserved)
    }

    /**
     * 확정된 대표자 일행에 동행자를 더한다(#169). 정원은 일반 신청과 같은 박람회 잠금·좌석 예약으로 지킨다.
     * 자리가 모자라면 대기열 뒤에 세우고, 일반 대기 일행과 같은 승급(#144: 전날 전 자동, 전날부터 참여 제안)으로 RESERVED가 된다.
     * 저장·확정은 기존 RESERVED 복구 경로([recover])가 끝낸다.
     */
    fun addCompanions(
        expoId: UUID,
        applicationId: Long,
        party: OwnerApplicationParty,
        added: List<JsonNode>,
        idempotencyKey: String?,
    ): CompanionAddition {
        val sessionId =
            repository.owned(expoId, applicationId)?.first?.sessionId
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "신청을 찾을 수 없습니다.")
        val observation = companions.observe(expoId, sessionId)
        val fingerprint = mapper.writeValueAsString(mapOf("companionsOf" to applicationId, "companions" to added))
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        val reserved =
            requireNotNull(
                ledger.transactions.execute {
                    ledger.lock(expoId)
                    reservations.find(expoId, idempotencyKey, fingerprint)?.let {
                        if (it.command != fingerprint) conflict("같은 신청 키의 내용이 다릅니다.")
                        return@execute it.requestId
                    }
                    PreregisterSeatCategory.requireOpen(
                        observation.occupationOpenAt,
                        waitlist.now(),
                        added.map { it.path("occupation").asString() },
                    )
                    val availability = companions.evaluate(expoId, party, applicationId, observation, persist = true).require()
                    if (added.size > availability.remaining) conflict(availability.message)
                    val waits = availability.waits(added.size) ?: conflict("일행 전원을 수용할 수 없습니다.")
                    val categories =
                        added.map { PreregisterSeatCategory.of(it.path("occupation").asString()) }.let { seats ->
                            seats.count { it == PreregisterSeatCategory.TEACHER } to seats.count { it == PreregisterSeatCategory.OTHER }
                        }
                    // #173: target sessions take no waiting addition, and each added category must fit its own seats.
                    availability.definition?.takeIf { it.targeted }?.let { definition ->
                        val (teacher, other) =
                            PreregisterSeatCategory.remaining(definition, repository.seatUsage(expoId, availability.sessionId))
                        if (waits || teacher < categories.first || other < categories.second) conflict("일행 전원을 수용할 수 없습니다.")
                    }
                    // 이미 일행에 있는 사람을 다시 보내면 User가 새로 만들지 않고 QR만 다시 보낸다. 미리 거절한다.
                    // #225: User처럼 대표자를 빼고 동행자의 이름·구분·학교로 같은 사람을 가린다. 학교를 싣지 않는 옛 User 응답이면 이름·구분만 본다.
                    val known = party.participants.filterNot { it.representative }
                    if (added.any { candidate ->
                            val school =
                                candidate
                                    .path("school")
                                    .asString()
                                    .trim()
                                    .ifEmpty { null }
                            known.any {
                                it.name.trim() == candidate.path("name").asString().trim() &&
                                    it.occupation == candidate.path("occupation").asString() &&
                                    (!it.schoolReported || it.school == school)
                            }
                        }
                    ) {
                        conflict("이미 일행에 있는 동행자입니다.")
                    }
                    val snapshot = requireNotNull(availability.snapshot)
                    val answers = mapper.readTree(snapshot.informationJson).deepCopy().asObject()
                    snapshot.questions.orEmpty().filter { it.formType == "COMPANION" }.forEach {
                        answers.set(it.title, mapper.createArrayNode().apply { if (it.title == availability.companionTitle) addAll(added) })
                    }
                    // 같은 대표자 정보에 새 동행자만 담아 새 User requestId로 다시 저장한다. 대표자는 새로 만들어지지 않는다.
                    val request =
                        snapshot.copy(
                            informationJson = mapper.writeValueAsString(answers),
                            requestId = UUID.randomUUID().toString(),
                        )
                    reservations.insertCompanions(
                        expoId,
                        key,
                        availability.sessionId,
                        fingerprint,
                        request,
                        added.size,
                        availability.representativeId,
                        waits,
                        categories,
                    )
                    key
                },
            )
        recover(expoId, reserved)
        return requireNotNull(
            ledger.transactions.execute {
                val saved = reservations.find(expoId, reserved, "") ?: return@execute CompanionAddition("CONFIRMED", emptyList(), null)
                when {
                    saved.state == "WAITING" -> {
                        CompanionAddition("WAITING", emptyList(), reservations.position(saved))
                    }

                    saved.state == "RESERVED" -> {
                        CompanionAddition("PROCESSING", emptyList(), null)
                    }

                    saved.withdrawn -> {
                        throw WaitlistWithdrawnException()
                    }

                    saved.state == "REJECTED" -> {
                        throw ResponseStatusException(HttpStatus.valueOf(requireNotNull(saved.errorStatus)), "참가자 저장이 거절되었습니다.")
                    }

                    else -> {
                        CompanionAddition("CONFIRMED", saved.result?.participantIds.orEmpty(), null)
                    }
                }
            },
        )
    }

    /**
     * 이미 저장된 일행의 다른 회차 추가(#199). 참가자와 QR이 User에 있으므로 User에 저장하지 않고 처음 신청 답변을 다시 쓴다.
     * 신청 뒤 사전등록 폼이 바뀌었으면 다시 입력해야 한다. 정원·대기·승급은 일반 신청과 같고 확정은 [recover]가 끝낸다.
     */
    fun addSession(
        expoId: UUID,
        party: OwnerApplicationParty,
        sessionId: Long,
        participantIds: List<Long>,
        idempotencyKey: String?,
    ): PreregisterRegistrationReceipt {
        if (!attendanceEnabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "참여 서비스 회차 통지를 활성화해야 합니다.")
        }
        val representativeId = party.representativeId ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "신청한 일행이 없습니다.")
        val attendees =
            participantIds.map { id ->
                party.participants.find { it.participantId == id } ?: badSessionRequest("일행에 없는 참가자입니다.")
            }
        // Q1: 대표자는 빠져도 되지만 학생만으로는 회차에 갈 수 없다.
        if (attendees.all { it.occupation in CompanionAnswers.students }) badSessionRequest("학생이 아닌 참석자가 1명 이상 있어야 합니다.")
        val observation = companions.observe(expoId, sessionId)
        val fingerprint =
            mapper.writeValueAsString(
                mapOf(
                    "sessionOf" to representativeId,
                    "sessionId" to sessionId,
                    "participantIds" to participantIds.sorted(),
                ),
            )
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        val reserved =
            requireNotNull(
                ledger.transactions.execute {
                    ledger.lock(expoId)
                    reservations.find(expoId, idempotencyKey, fingerprint)?.let {
                        if (it.command != fingerprint) conflict("같은 신청 키의 내용이 다릅니다.")
                        return@execute it.requestId
                    }
                    PreregisterSeatCategory.requireOpen(observation.occupationOpenAt, waitlist.now(), attendees.map { it.occupation })
                    val original =
                        reservations.partyRegistration(expoId, representativeId)
                            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "다시 쓸 사전등록 답변이 없습니다.")
                    if (original.formId != observation.formId) throw PreregisterFormChangedException()
                    val definition = ledger.current(expoId, sessionId, observation.definition)
                    if (definition.closed) conflict("마감된 회차입니다.")
                    if (!waitlist.now().isBefore(definition.startedAt)) conflict("이미 시작된 회차입니다.")
                    if (!observation.registrationOpen) conflict("사전 등록 기간이 아닙니다.")
                    val phone = original.phoneNumber.filter { it in '0'..'9' }
                    if (participantIds.any { repository.active(expoId, sessionId, it) != null } ||
                        reservations.sessionInProgress(expoId, sessionId, phone)
                    ) {
                        conflict("이미 신청한 회차입니다.")
                    }
                    if (repository.overlapping(expoId, sessionId, participantIds, definition)) conflict("확정된 다른 회차와 시간이 겹칩니다.")
                    // #223: 승급될 수 있는 다른 회차의 대기·선점도 겹침으로 본다. 나중에 승급이 겹침으로 거절되는 것을 막는다.
                    if (reservations.overlappingPending(expoId, sessionId, phone, participantIds, definition, waitlist.now())) {
                        conflict("대기 중인 다른 회차와 시간이 겹칩니다.")
                    }
                    val categories =
                        attendees.map { PreregisterSeatCategory.of(it.occupation) }.let { seats ->
                            seats.count { it == PreregisterSeatCategory.TEACHER } to seats.count { it == PreregisterSeatCategory.OTHER }
                        }
                    val applicants = participantIds.size
                    val queued = reservations.waitingApplicants(expoId, sessionId)
                    val free = definition.capacity.toLong() - repository.occupied(expoId, sessionId)
                    val waits =
                        if (definition.targeted) {
                            if (categories.first + categories.second != applicants) {
                                badSessionRequest("대상별 정원 회차는 참석자 직업이 필요합니다.")
                            }
                            val (teacher, other) = PreregisterSeatCategory.remaining(definition, repository.seatUsage(expoId, sessionId))
                            if (queued > 0 || teacher < categories.first || other < categories.second || free < applicants) {
                                conflict("일행 전원을 수용할 수 없습니다.")
                            }
                            false
                        } else if (queued == 0L && free >= applicants) {
                            false
                        } else if (waitlistIntake && waitlist.accepts(definition) &&
                            (definition.waitingCapacity ?: 0) - queued >= applicants
                        ) {
                            true
                        } else {
                            conflict("일행 전원을 수용할 수 없습니다.")
                        }
                    reservations.insertSessionAddition(
                        expoId,
                        key,
                        sessionId,
                        fingerprint,
                        sessionRequest(expoId, original, representativeId, attendees),
                        ParticipantRegistrationResult(
                            representativeId,
                            original.phoneNumber,
                            participantIds,
                            party.participants.map { it.participantId },
                        ),
                        waits,
                        categories,
                    )
                    key
                },
            )
        return deliver(expoId, reserved)
    }

    // 처음 신청 답변에서 동행자 답변만 이번 참석자로 바꾼다. 나중에 더한 동행자(#169)의 답변도 함께 찾는다.
    private fun sessionRequest(
        expoId: UUID,
        original: ParticipantRegistration,
        representativeId: Long,
        attendees: List<ManagedOwnerParticipant>,
    ): ParticipantRegistration {
        val answered =
            reservations.partyRequests(expoId, representativeId).flatMap { request ->
                val answers = mapper.readTree(request.informationJson)
                request.questions
                    .orEmpty()
                    .filter { it.formType == "COMPANION" }
                    .flatMap { answers.get(it.title)?.toList().orEmpty() }
            }
        val chosen =
            attendees.filter { it.participantId != representativeId }.mapNotNull { person ->
                answered.firstOrNull {
                    it.path("name").asString().trim() == person.name.trim() && it.path("occupation").asString() == person.occupation
                }
            }
        val answers = mapper.readTree(original.informationJson).deepCopy().asObject()
        val titles =
            original.questions
                .orEmpty()
                .filter { it.formType == "COMPANION" }
                .map { it.title }
        titles.forEach { title -> answers.set(title, mapper.createArrayNode().apply { if (title == titles.first()) addAll(chosen) }) }
        return original.copy(
            informationJson = mapper.writeValueAsString(answers),
            requestId = UUID.randomUUID().toString(),
            releasedParticipantIds = null,
        )
    }

    private fun withReleased(saved: PreregisterReservation): PreregisterReservation {
        val ids =
            saved.companionOf?.let { reservations.cancelledCompanions(saved.expoId, it) }
                ?: saved.userRequest.phoneNumber
                    .filter { it in '0'..'9' }
                    .takeIf { it.isNotEmpty() }
                    ?.let { reservations.cancelledCompanionsOfPhone(saved.expoId, it) }
                    .orEmpty()
        if (ids.isEmpty()) return saved
        reservations.saveReleased(saved, ids)
        return saved.copy(userRequest = saved.userRequest.copy(releasedParticipantIds = ids))
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
                    // 대기했다 승급된 동행자 추가는 그사이 대표자 신청이 바뀌었을 수 있다. User에 저장하기 전에 좌석을 돌려준다.
                    if (saved.companionOf != null && saved.result == null && !representativeConfirmed(expoId, saved)) {
                        reservations.finish(saved, 409)
                        return@execute saved.copy(state = "REJECTED", errorStatus = 409)
                    }
                    if (repository.expoDeleted(expoId) && saved.attempts == 0) {
                        reservations.finish(saved, 409)
                        saved.copy(state = "REJECTED", errorStatus = 409)
                    } else {
                        // Persist attempted delivery before HTTP, even if the process dies after User commits.
                        // #190: 처음 User로 보내는 시도에서만 취소된 동행자를 계산해 요청에 고정한다. 이후 재시도는 같은 본문이다.
                        val prepared =
                            if (saved.attempts == 0 && saved.result == null &&
                                saved.errorStatus == null
                            ) {
                                withReleased(saved)
                            } else {
                                saved
                            }
                        if (saved.errorStatus == null || repository.expoDeleted(expoId)) reservations.retry(saved)
                        prepared
                    }
                },
            )
        // Waiting parties are delivered to User only after promotion moves them back to RESERVED.
        if (reservation.state == "COMPLETED" || reservation.state == "WAITING") return
        if (reservation.withdrawn) throw WaitlistWithdrawnException()
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
                                retryOrReview(current)
                            }
                        }
                        false
                    }
                if (completed == true) return
                // A definite User rejection frees the seats.
                if (ex is ParticipantSaveRejectedException) waitlist.promoteQuietly(expoId, reservation.sessionId)
                throw ex
            }
        val categories =
            try {
                seatCategories(expoId, reservation, result)
            } catch (ex: ResponseStatusException) {
                // The User save already happened; keep its result and the seats until the category lookup succeeds or is reviewed.
                ledger.transactions.executeWithoutResult {
                    repository.lock(expoId)
                    val current = reservations.find(expoId, requestId, "") ?: return@executeWithoutResult
                    if (current.state == "RESERVED" && current.errorStatus == null) {
                        reservations.result(current, result)
                        if (ex is ParticipantIdsMismatchException) reservations.needsReview(current, 502) else retryOrReview(current)
                    }
                }
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
                reserved.companionOf?.let { return@execute completeCompanions(expoId, reserved, result, it, categories) }
                if (reserved.sessionAddition) return@execute completeSession(expoId, reserved, result, categories)
                val ids = result.groupParticipantIds
                if (repository.expoDeleted(expoId)) {
                    ids.forEach { attendance.cancelled(expoId, it, reserved.sessionId) }
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
                if (ids.any { repository.active(expoId, reserved.sessionId, it) != null } ||
                    repository
                        .definition(
                            expoId,
                            reserved.sessionId,
                        )?.let { repository.overlapping(expoId, reserved.sessionId, ids, it) } ==
                    true
                ) {
                    reservations.needsReview(reserved, 409)
                    return@execute ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "이미 활성 신청이 있는 참가자입니다. 좌석을 유지합니다.",
                    )
                }
                ids.forEach {
                    repository.insert(
                        expoId,
                        reserved.sessionId,
                        result.participantId,
                        it,
                        "CONFIRMED",
                        reserved.requestId,
                        seatCategory = categories[it],
                    )
                    attendance.confirmed(expoId, it, reserved.sessionId)
                }
                reservations.enqueueCounts(expoId, ids)
                reservations.finish(reserved)
                null
            }
        if (failure != null) throw failure
    }

    private fun representativeConfirmed(
        expoId: UUID,
        reserved: PreregisterReservation,
    ): Boolean = repository.active(expoId, reserved.sessionId, requireNotNull(reserved.companionOf))?.status == "CONFIRMED"

    // 동행자 추가 예약의 완료. User는 새로 만든 동행자만 participantIds로, 일행 전체를 groupParticipantIds로 준다.
    private fun completeCompanions(
        expoId: UUID,
        reserved: PreregisterReservation,
        result: ParticipantRegistrationResult,
        representativeId: Long,
        categories: Map<Long, String?>,
    ): ResponseStatusException? {
        val ids = result.participantIds
        if (repository.expoDeleted(expoId)) {
            ids.forEach { attendance.cancelled(expoId, it, reserved.sessionId) }
            reservations.finish(reserved, 409)
            return ResponseStatusException(HttpStatus.CONFLICT, "삭제된 박람회입니다.")
        }
        if (result.participantId != representativeId || ids.size != reserved.applicants || representativeId in ids ||
            !result.groupParticipantIds.containsAll(ids) || ids.any { it <= 0 } || ids.distinct().size != ids.size
        ) {
            reservations.needsReview(reserved, 502)
            return ResponseStatusException(HttpStatus.BAD_GATEWAY, "User 참가자 목록과 선점 인원이 일치하지 않습니다. 좌석을 유지합니다.")
        }
        if (repository.active(expoId, reserved.sessionId, representativeId)?.status != "CONFIRMED" ||
            ids.any { repository.active(expoId, reserved.sessionId, it) != null }
        ) {
            reservations.needsReview(reserved, 409)
            return ResponseStatusException(HttpStatus.CONFLICT, "대표자 신청 또는 동행자 상태가 바뀌었습니다. 좌석을 유지합니다.")
        }
        ids.forEach {
            repository.insert(
                expoId,
                reserved.sessionId,
                representativeId,
                it,
                "CONFIRMED",
                reserved.requestId,
                ApplicationActorType.USER,
                representativeId,
                categories[it],
            )
            attendance.confirmed(expoId, it, reserved.sessionId)
        }
        reservations.enqueueCounts(expoId, ids)
        reservations.finish(reserved)
        return null
    }

    // 다른 회차 추가(#199)의 완료. User에 저장한 것이 없으므로 그사이 같은 회차를 신청했거나 시간이 겹치게 됐으면 좌석을 돌려준다.
    private fun completeSession(
        expoId: UUID,
        reserved: PreregisterReservation,
        result: ParticipantRegistrationResult,
        categories: Map<Long, String?>,
    ): ResponseStatusException? {
        val ids = result.participantIds
        val definition = repository.definition(expoId, reserved.sessionId)
        if (repository.expoDeleted(expoId) || definition == null ||
            ids.any { repository.active(expoId, reserved.sessionId, it) != null } ||
            repository.overlapping(expoId, reserved.sessionId, ids, definition)
        ) {
            reservations.finish(reserved, 409)
            return ResponseStatusException(HttpStatus.CONFLICT, "이미 신청했거나 시간이 겹치는 회차입니다.")
        }
        ids.forEach {
            repository.insert(
                expoId,
                reserved.sessionId,
                result.participantId,
                it,
                "CONFIRMED",
                reserved.requestId,
                ApplicationActorType.USER,
                result.participantId,
                categories[it],
            )
            attendance.confirmed(expoId, it, reserved.sessionId)
        }
        reservations.finish(reserved)
        return null
    }

    // #173: the representative keeps its submitted answer. User returns companion IDs without their answers, so a party
    // with mixed companion categories is read back from User and must match the reserved categories exactly.
    // A companion addition (#169) only creates the new companions in participantIds; the representative already has a row.
    private fun seatCategories(
        expoId: UUID,
        reservation: PreregisterReservation,
        result: ParticipantRegistrationResult,
    ): Map<Long, String?> {
        // #199: 참석자의 지금 User 직업으로 좌석 구분을 정한다. 직업이 없으면 미상이다.
        if (reservation.sessionAddition) {
            return gateway
                .participantOccupations(expoId.toString(), result.participantIds.toSet())
                .mapValues { PreregisterSeatCategory.of(it.value) }
        }
        val addition = reservation.companionOf != null
        val ids = if (addition) result.participantIds else result.groupParticipantIds
        // Malformed results and deleted expos are handled by the completion step; reservations from before V31 stay unknown.
        if (reservation.categories == null || repository.expoDeleted(expoId) ||
            ids.size != reservation.applicants || (result.participantId in ids) == addition ||
            ids.distinct().size != ids.size
        ) {
            return emptyMap()
        }
        val occupations = PreregisterSeatCategory.partyOccupations(reservation.userRequest, mapper)
        val expected = occupations.drop(1).map(PreregisterSeatCategory::of)
        val companions = ids - result.participantId
        val actual =
            if (expected.distinct().size <= 1) {
                companions.associateWith { expected.firstOrNull() }
            } else {
                gateway
                    .participantOccupations(expoId.toString(), companions.toSet())
                    .mapValues { PreregisterSeatCategory.of(it.value) }
            }
        if (actual.values.groupingBy { it }.eachCount() != expected.groupingBy { it }.eachCount()) {
            throw ParticipantIdsMismatchException()
        }
        return if (addition) actual else actual + (result.participantId to PreregisterSeatCategory.of(occupations.first()))
    }
}

/** 신청 뒤 사전등록 폼이 바뀌어 다른 회차 추가에 처음 답변을 다시 쓸 수 없다(#199). */
class PreregisterFormChangedException : ResponseStatusException(HttpStatus.CONFLICT, "FORM_CHANGED")

/** 동행자 추가 결과. 자리가 있으면 CONFIRMED, 대기열에 섰으면 WAITING과 순번, User 저장 중이면 PROCESSING이다. */
data class CompanionAddition(
    val status: String,
    val participantIds: List<Long>,
    val position: Int?,
)
