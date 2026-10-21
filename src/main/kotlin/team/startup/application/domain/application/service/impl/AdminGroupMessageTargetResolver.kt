package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationRow
import team.startup.application.domain.application.presentation.dto.AdminApplicationSort
import team.startup.application.domain.application.presentation.dto.AdminApplicationStatus
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageChannel
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageResolvedTargets
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageScopeKind
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageSource
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageTarget
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageTemplate
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageTemplateVariables
import team.startup.application.domain.application.repository.AdminApplicationQueryRepository
import team.startup.application.domain.application.time.ExpoProgramTime
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

@Component
class AdminGroupMessageTargetResolver(
    private val applications: AdminApplicationQueryRepository,
    private val gateway: AdminGroupMessageGateway,
) {
    fun resolve(
        expoId: UUID,
        source: AdminGroupMessageSource,
    ): AdminGroupMessageResolvedTargets {
        val rows = rows(expoId, source)
        val participants = gateway.participants(expoId, rows.map { it.participantId })
        val representativeIds = participants.values.map { it.representativeId ?: it.participantId }.distinct()
        val representatives = gateway.participants(expoId, representativeIds)
        val kakaoTemplate = source.templateId?.let(::approvedKakaoTemplate)
        val expoName = kakaoTemplate?.variableMapping?.expoName?.let { gateway.expoName(expoId) }
        val personalized =
            when (source.channel) {
                AdminGroupMessageChannel.SMS -> {
                    PLACEHOLDERS.any(requireNotNull(source.body)::contains)
                }

                AdminGroupMessageChannel.KAKAO -> {
                    requireNotNull(kakaoTemplate).variableMapping.run {
                        listOf(name, programName, sessionName, location, status, applicationUrl, applicationSummary).any { it != null }
                    }
                }
            }
        // 매핑·본문이 쓸 때만 보낸다. 쓰지 않는 기존 템플릿은 Notification #101 배포 전에도 그대로 동작한다.
        val summaryUsed =
            when (source.channel) {
                AdminGroupMessageChannel.SMS -> requireNotNull(source.body).contains("{신청내역}")
                AdminGroupMessageChannel.KAKAO -> requireNotNull(kakaoTemplate).variableMapping.applicationSummary != null
            }
        val seenPhones = mutableSetOf<String>()
        val targets =
            rows.mapNotNull { row ->
                val participant = participants.getValue(row.participantId)
                val representative = representatives.getValue(participant.representativeId ?: participant.participantId)
                val phone = normalizePhone(representative.phoneNumber)
                val variables = variables(row, participant.name, expoName, summaryUsed)
                val missing =
                    when {
                        phone == null -> {
                            "REPRESENTATIVE_PHONE_MISSING"
                        }

                        source.channel == AdminGroupMessageChannel.SMS &&
                            missingSmsValue(requireNotNull(source.body), variables, row.status) -> {
                            "TEMPLATE_DATA_MISSING"
                        }

                        source.channel == AdminGroupMessageChannel.KAKAO && missingKakaoValue(requireNotNull(kakaoTemplate), variables) -> {
                            "TEMPLATE_DATA_MISSING"
                        }

                        else -> {
                            null
                        }
                    }
                if (!personalized && phone != null && missing == null && !seenPhones.add(phone)) {
                    null
                } else {
                    AdminGroupMessageTarget(
                        targetId = row.applicationId.toString(),
                        applicationId = row.applicationId,
                        participantId = row.participantId,
                        programType = row.programType,
                        recipientPhone = phone?.takeIf { missing == null },
                        content = if (missing == null) render(source, kakaoTemplate, variables, row.status) else null,
                        templateVariables = variables.takeIf { source.channel == AdminGroupMessageChannel.KAKAO && missing == null },
                        missingReason = missing,
                    )
                }
            }
        return AdminGroupMessageResolvedTargets(
            fingerprint(source, rows, participants, representatives, targets),
            targets,
            rows.size,
        )
    }

    private fun rows(
        expoId: UUID,
        source: AdminGroupMessageSource,
    ): List<AdminApplicationRow> {
        val scope = source.scope
        val rows =
            when (scope.kind) {
                AdminGroupMessageScopeKind.SELECTED -> {
                    val ids = requireNotNull(scope.applicationIds)
                    applications
                        .targets(
                            expoId,
                            AdminApplicationQuery(sort = AdminApplicationSort.CREATED_ASC),
                            ids.size,
                            ids,
                        ).also {
                            if (it.size != ids.size) {
                                throw ResponseStatusException(HttpStatus.NOT_FOUND, "하나 이상의 신청을 현재 행사에서 찾을 수 없습니다.")
                            }
                        }
                }

                AdminGroupMessageScopeKind.FILTERED -> {
                    applications.targets(expoId, requireNotNull(scope.filter).query, MAX_TARGETS + 1)
                }
            }
        if (rows.size > MAX_TARGETS) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "그룹 발송 대상은 최대 ${MAX_TARGETS}건입니다. 필터를 좁혀 주세요.")
        }
        return rows
    }

    private fun variables(
        row: AdminApplicationRow,
        name: String,
        expoName: String?,
        summaryUsed: Boolean,
    ): AdminGroupMessageTemplateVariables {
        fun text(name: String): String? =
            row.definition
                .path(name)
                .takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        val startedAt = text("startedAt")
        val endedAt = text("endedAt")
        val programName = text("title")
        val sessionName = startedAt?.let { sessionTime(it, endedAt) }
        val status = statusLabel(row.status)
        return AdminGroupMessageTemplateVariables(
            expoName = expoName,
            name = name,
            programName = programName,
            sessionName = sessionName,
            location = text("place"),
            status = status,
            applicationSummary =
                if (summaryUsed && programName != null && sessionName != null) {
                    "- 프로그램: $programName\n- 일시: $sessionName\n- 신청 상태: $status".takeIf { it.length <= MAX_SUMMARY_LENGTH }
                } else {
                    null
                },
        )
    }

    // 읽을 수 없는 일시는 원본 문자열을 보내지 않고 없는 값으로 보아 `TEMPLATE_DATA_MISSING`으로 남긴다.
    private fun sessionTime(
        startedAt: String,
        endedAt: String?,
    ): String? =
        try {
            ExpoProgramTime.display(startedAt, endedAt)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun approvedKakaoTemplate(templateId: String): AdminGroupMessageTemplate {
        val config = gateway.config()
        val channel = config.channels[AdminGroupMessageChannel.KAKAO]
        if (!config.enabled || channel?.enabled != true) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "카카오 채널이 준비되지 않았습니다.")
        }
        return channel.templates.singleOrNull { it.id == templateId }
            ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "승인된 카카오 템플릿을 찾을 수 없습니다.")
    }

    private fun missingSmsValue(
        body: String,
        variables: AdminGroupMessageTemplateVariables,
        status: AdminApplicationStatus,
    ): Boolean =
        (body.contains("{이름}") && variables.name == null) ||
            (body.contains("{프로그램}") && variables.programName == null) ||
            (body.contains("{일시}") && variables.sessionName == null) ||
            (body.contains("{장소}") && variables.location == null) ||
            (body.contains("{상태}") && statusLabel(status).isBlank()) ||
            (body.contains("{신청내역}") && variables.applicationSummary == null) ||
            // ponytail: QR code 공급 계약(User 일괄 조회)이 정해지기 전까지 링크는 늘 없는 값이다. 연수 신청도 일반 참가자 QR이라 같다.
            body.contains("{URL}")

    private fun missingKakaoValue(
        template: AdminGroupMessageTemplate,
        variables: AdminGroupMessageTemplateVariables,
    ): Boolean =
        (template.variableMapping.expoName != null && variables.expoName == null) ||
            (template.variableMapping.name != null && variables.name == null) ||
            (template.variableMapping.programName != null && variables.programName == null) ||
            (template.variableMapping.sessionName != null && variables.sessionName == null) ||
            (template.variableMapping.location != null && variables.location == null) ||
            (template.variableMapping.status != null && variables.status == null) ||
            (template.variableMapping.applicationSummary != null && variables.applicationSummary == null) ||
            template.variableMapping.applicationUrl != null

    private fun render(
        source: AdminGroupMessageSource,
        kakaoTemplate: AdminGroupMessageTemplate?,
        variables: AdminGroupMessageTemplateVariables,
        status: AdminApplicationStatus,
    ): String =
        when (source.channel) {
            AdminGroupMessageChannel.SMS -> {
                requireNotNull(source.body)
                    .replace("{이름}", requireNotNull(variables.name))
                    .replace("{프로그램}", variables.programName.orEmpty())
                    .replace("{일시}", variables.sessionName.orEmpty())
                    .replace("{장소}", variables.location.orEmpty())
                    .replace("{상태}", statusLabel(status))
                    .replace("{신청내역}", variables.applicationSummary.orEmpty())
            }

            AdminGroupMessageChannel.KAKAO -> {
                val template = requireNotNull(kakaoTemplate)
                // Notification #101 비교와 같게: 고정값 위에 매핑 값을 덮고 `#{…}`를 한 번에 치환한다(치환한 값은 다시 보지 않는다).
                val values =
                    template.fixedVariables.orEmpty() +
                        listOf(
                            template.variableMapping.expoName to variables.expoName,
                            template.variableMapping.name to variables.name,
                            template.variableMapping.programName to variables.programName,
                            template.variableMapping.sessionName to variables.sessionName,
                            template.variableMapping.location to variables.location,
                            template.variableMapping.status to variables.status,
                            template.variableMapping.applicationSummary to variables.applicationSummary,
                        ).mapNotNull { (placeholder, value) -> placeholder?.let { it to requireNotNull(value) } }
                KAKAO_PLACEHOLDER.replace(template.content) { values[it.value] ?: it.value }
            }
        }

    private fun statusLabel(status: AdminApplicationStatus) =
        when (status) {
            AdminApplicationStatus.CONFIRMED -> "확정"
            AdminApplicationStatus.WAITING -> "대기"
            AdminApplicationStatus.CANCELLED -> "취소"
        }

    private fun normalizePhone(raw: String?): String? =
        raw
            ?.filter(Char::isDigit)
            ?.takeIf { it.matches(Regex("0[0-9]{8,10}")) }

    private fun fingerprint(
        source: AdminGroupMessageSource,
        rows: List<AdminApplicationRow>,
        participants: Map<Long, AdminGroupMessageParticipant>,
        representatives: Map<Long, AdminGroupMessageParticipant>,
        targets: List<AdminGroupMessageTarget>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun add(value: Any?) {
            val bytes = (value?.toString() ?: "<null>").toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(source.channel)
        add(source.body)
        add(source.templateId)
        add(source.fallbackToSms)
        rows.sortedBy { it.applicationId }.forEach { row ->
            val participant = participants.getValue(row.participantId)
            val representative = representatives.getValue(participant.representativeId ?: participant.participantId)
            add(row.applicationId)
            add(row.participantId)
            add(participant.name)
            add(participant.representativeId)
            add(normalizePhone(representative.phoneNumber))
            add(row.programType)
            add(row.programId)
            add(row.groupId)
            add(row.status)
            add(row.definition.toString())
        }
        targets.sortedBy { it.targetId }.forEach { target ->
            add(target.targetId)
            add(target.applicationId)
            add(target.participantId)
            add(target.programType)
            add(target.recipientPhone)
            add(target.content)
            add(target.templateVariables?.expoName)
            add(target.templateVariables?.name)
            add(target.templateVariables?.programName)
            add(target.templateVariables?.sessionName)
            add(target.templateVariables?.location)
            add(target.templateVariables?.status)
            add(target.templateVariables?.applicationSummary)
            add(target.missingReason)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val MAX_TARGETS = 5_000
        const val MAX_SUMMARY_LENGTH = 500
        val KAKAO_PLACEHOLDER = Regex("#\\{[^{}]+}")
        val PLACEHOLDERS = listOf("{이름}", "{프로그램}", "{일시}", "{장소}", "{상태}", "{URL}", "{신청내역}")
    }
}
