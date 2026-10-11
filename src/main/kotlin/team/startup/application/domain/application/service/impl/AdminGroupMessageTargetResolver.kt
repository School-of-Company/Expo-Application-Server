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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
        val personalized =
            when (source.channel) {
                AdminGroupMessageChannel.SMS -> {
                    PLACEHOLDERS.any(requireNotNull(source.body)::contains)
                }

                AdminGroupMessageChannel.KAKAO -> {
                    requireNotNull(kakaoTemplate).variableMapping.run {
                        listOf(name, programName, sessionName, location).any { it != null }
                    }
                }
            }
        val seenPhones = mutableSetOf<String>()
        val targets =
            rows.mapNotNull { row ->
                val participant = participants.getValue(row.participantId)
                val representative = representatives.getValue(participant.representativeId ?: participant.participantId)
                val phone = normalizePhone(representative.phoneNumber)
                val variables = variables(row, participant.name)
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
    ): AdminGroupMessageTemplateVariables {
        fun text(name: String): String? =
            row.definition
                .path(name)
                .takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        val startedAt = text("startedAt")
        val endedAt = text("endedAt")
        return AdminGroupMessageTemplateVariables(
            name = name,
            programName = text("title"),
            sessionName = listOfNotNull(startedAt, endedAt).joinToString(" ~ ").takeIf { it.isNotBlank() },
            location = text("place"),
        )
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
            (body.contains("{상태}") && statusLabel(status).isBlank())

    private fun missingKakaoValue(
        template: AdminGroupMessageTemplate,
        variables: AdminGroupMessageTemplateVariables,
    ): Boolean =
        (template.variableMapping.name != null && variables.name == null) ||
            (template.variableMapping.programName != null && variables.programName == null) ||
            (template.variableMapping.sessionName != null && variables.sessionName == null) ||
            (template.variableMapping.location != null && variables.location == null)

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
            }

            AdminGroupMessageChannel.KAKAO -> {
                val template = requireNotNull(kakaoTemplate)
                listOf(
                    template.variableMapping.name to variables.name,
                    template.variableMapping.programName to variables.programName,
                    template.variableMapping.sessionName to variables.sessionName,
                    template.variableMapping.location to variables.location,
                ).fold(template.content) { content, (placeholder, value) ->
                    if (placeholder == null) content else content.replace(placeholder, requireNotNull(value))
                }
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
            add(target.templateVariables?.name)
            add(target.templateVariables?.programName)
            add(target.templateVariables?.sessionName)
            add(target.templateVariables?.location)
            add(target.missingReason)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val MAX_TARGETS = 5_000
        val PLACEHOLDERS = listOf("{이름}", "{프로그램}", "{일시}", "{장소}", "{상태}")
    }
}
