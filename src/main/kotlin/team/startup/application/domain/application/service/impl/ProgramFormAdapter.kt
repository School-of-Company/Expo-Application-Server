package team.startup.application.domain.application.service.impl

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.service.ProgramFormProvider
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

@AutoConfiguration
class ProgramFormAdapterConfig {
    @Bean
    @ConditionalOnMissingBean(ProgramFormProvider::class)
    fun programFormProvider(gateway: RegistrationGateway): ProgramFormProvider = ProgramFormAdapter(gateway)
}

class ProgramFormAdapter(
    private val gateway: RegistrationGateway,
) : ProgramFormProvider {
    override fun loadAndValidate(
        expoId: String,
        formId: UUID,
        version: Long,
        answers: JsonNode,
    ): ProgramFormSnapshot {
        val form = gateway.programForm(formId)
        val snapshot =
            try {
                require(form.path("id").isString && form.path("expoId").isString && form.path("purpose").isString)
                require(form.path("version").isIntegralNumber && form.path("version").canConvertToLong())
                require(form.path("version").asLong() > 0 && form.path("dynamicForm").isArray)
                ProgramFormSnapshot(
                    UUID.fromString(form.path("expoId").stringValue()).toString(),
                    UUID.fromString(form.path("id").stringValue()),
                    form.path("purpose").stringValue(),
                    form.path("version").asLong(),
                    Instant.parse(form.path("startDate").stringValue()),
                    Instant.parse(form.path("endDate").stringValue()),
                    form.path("dynamicForm"),
                ).also { require(it.startedAt.isBefore(it.endedAt)) }
            } catch (ex: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 폼 응답이 올바르지 않습니다.", ex)
            }
        if (snapshot.id != formId || snapshot.expoId != expoId || snapshot.purpose != "PROGRAM" || snapshot.version != version) {
            throw ProgramApplicationConflictException("프로그램 폼 또는 버전이 변경되었습니다.")
        }
        validate(snapshot.questions, answers)
        return snapshot
    }

    private fun validate(
        questions: JsonNode,
        answers: JsonNode,
    ) {
        val fields = questions.toList()
        val ids = fields.map { it.path("id").asLong().toString() }
        fields.forEachIndexed { index, field ->
            if (!field.path("id").isIntegralNumber || !field.path("id").canConvertToLong() || field.path("id").asLong() <= 0 ||
                !field.path("requiredStatus").isBoolean || !field.path("jsonData").isObject || !field.path("formType").isString ||
                !field.path("title").isString || !field.path("dynamicFormType").isString
            ) {
                invalidDefinition()
            }
            val other = field.get("otherJson") ?: invalidDefinition()
            if (!other.isNull && (!other.isObject || !other.path("hasEtc").isBoolean)) invalidDefinition()
            val type = field.path("formType").stringValue()
            // shortcut: IMAGE and free-form other choices lack an agreed answer DTO; enable after supplier/Client agreement.
            if (type == "IMAGE" || field.path("otherJson").path("hasEtc").asBoolean()) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지원되지 않는 프로그램 답변 계약입니다.")
            }
            if (type !in setOf("SENTENCE", "CHECKBOX", "DROPDOWN", "MULTIPLE")) invalidDefinition()
            val options = field.path("jsonData")
            if ((type in setOf("DROPDOWN", "MULTIPLE")) != (options.size() > 0)) invalidDefinition()
            options.properties().forEach { (_, option) ->
                if (!option.isString &&
                    (!option.isObject || !option.path("value").isString || !option.path("isAlwaysSelected").isBoolean)
                ) {
                    invalidDefinition()
                }
            }
            val max = field.path("otherJson").get("maxSelection")
            if (max != null && (!max.isIntegralNumber || !max.canConvertToInt() || max.asInt() <= 0)) invalidDefinition()
            val conditional = field.path("otherJson").get("conditional")
            if (conditional != null) {
                val parent = conditional.path("parentIndex")
                if (!parent.isIntegralNumber || !parent.canConvertToInt() || parent.asInt() !in 0 until index) invalidDefinition()
                val parentField = fields[parent.asInt()]
                if (parentField.path("formType").stringValue() !in setOf("DROPDOWN", "MULTIPLE")) invalidDefinition()
                val single = conditional.get("triggerValue")
                val many = conditional.get("triggerValues")
                if ((single == null) == (many == null)) invalidDefinition()
                val triggers = if (single != null) listOf(single) else many!!.toList()
                if (many != null && (!many.isArray || many.isEmpty)) invalidDefinition()
                if (triggers.any { !it.isString || !parentField.path("jsonData").has(it.stringValue()) }) invalidDefinition()
            }
        }
        if (ids.distinct().size != ids.size) invalidDefinition()
        if (!answers.isObject || answers.properties().any { it.key !in ids }) badAnswer()
        val visible = mutableListOf<Boolean>()
        fields.forEachIndexed { index, field ->
            val condition = field.path("otherJson").get("conditional")
            val active =
                if (condition == null) {
                    true
                } else {
                    val parent = condition.path("parentIndex").asInt()
                    val triggers = condition.get("triggerValues")?.toList() ?: listOf(condition.path("triggerValue"))
                    val value = answers.get(ids[parent])
                    visible[parent] &&
                        triggers.any { trigger -> value == trigger || (value?.isArray == true && value.any { it == trigger }) }
                }
            visible.add(active)
            val answer = answers.get(ids[index])
            val empty =
                answer == null || answer.isNull || (answer.isString && answer.stringValue().isBlank()) || (answer.isArray && answer.isEmpty)
            if (!active) {
                if (!empty) badAnswer()
            } else if (empty) {
                if (field.path("requiredStatus").asBoolean()) badAnswer()
            } else {
                when (field.path("formType").stringValue()) {
                    "SENTENCE" -> {
                        if (!answer.isString) badAnswer()
                    }

                    "CHECKBOX" -> {
                        if (!answer.isBoolean) badAnswer()
                    }

                    "DROPDOWN" -> {
                        if (!answer.isString || !field.path("jsonData").has(answer.stringValue())) badAnswer()
                    }

                    "MULTIPLE" -> {
                        if (!answer.isArray) badAnswer()
                        val selected = answer.toList()
                        if (selected.any { !it.isString || !field.path("jsonData").has(it.stringValue()) } ||
                            selected.distinct().size != selected.size ||
                            selected.size > (field.path("otherJson").get("maxSelection")?.asInt() ?: field.path("jsonData").size())
                        ) {
                            badAnswer()
                        }
                    }
                }
            }
            if (active) {
                field.path("jsonData").properties().forEach { (key, option) ->
                    if (option.path("isAlwaysSelected").asBoolean() &&
                        (
                            answer == null || (
                                !(answer.isString && answer.stringValue() == key) &&
                                    !(answer.isArray && answer.any { it.isString && it.stringValue() == key })
                            )
                        )
                    ) {
                        badAnswer()
                    }
                }
            }
        }
    }

    private fun invalidDefinition(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 문항 응답이 올바르지 않습니다.")

    private fun badAnswer(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "프로그램 문항 답변이 올바르지 않습니다.")
}
