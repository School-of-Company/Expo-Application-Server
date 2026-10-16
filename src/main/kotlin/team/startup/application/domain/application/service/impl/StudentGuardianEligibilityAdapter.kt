package team.startup.application.domain.application.service.impl

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramGuardianPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.StudentGuardianEligibilityQuery
import team.startup.application.domain.application.presentation.dto.StudentGuardianSubmission
import team.startup.application.domain.application.presentation.dto.StudentGuardianValidationPhase
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@AutoConfiguration(after = [ProgramFormAdapterConfig::class])
class StudentGuardianEligibilityAdapterConfig {
    @Bean
    @ConditionalOnMissingBean(StudentGuardianEligibilityProvider::class)
    fun studentGuardianEligibilityProvider(
        gateway: RegistrationGateway,
        forms: ProgramFormProvider,
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ): StudentGuardianEligibilityProvider = StudentGuardianEligibilityAdapter(gateway, forms, jdbc, mapper)
}

class StudentGuardianEligibilityAdapter(
    private val gateway: RegistrationGateway,
    private val forms: ProgramFormProvider,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : StudentGuardianEligibilityProvider {
    override fun validate(query: StudentGuardianEligibilityQuery) {
        val definition = query.definition
        val policy = definition.policy ?: conflict()
        val guardian = policy.guardianPolicy ?: conflict()
        if (!guardian.valid()) invalidPolicy()
        val students = query.links.map { it.studentId }.toSet()
        val guardians = query.links.map { it.guardianId }.toSet()
        if (students.isEmpty() || students.intersect(guardians).isNotEmpty() ||
            students + guardians != query.participants || query.links.size != students.size ||
            !guardians.containsAll(query.reusedGuardianIds)
        ) {
            denied()
        }
        val occupations = gateway.participantOccupations(definition.expoId, query.participants)
        if (students.any { occupations[it] != "ELEMENTARY_STUDENT" } || guardians.any { occupations[it] != "PARENT" } ||
            occupations.values.any { it !in policy.allowedOccupations }
        ) {
            denied()
        }
        val submitted = query.submissions.associateBy { it.participantId }
        query.participants.forEach { id ->
            val item =
                if (query.phase == StudentGuardianValidationPhase.PROMOTION || id in query.reusedGuardianIds) {
                    saved(definition, id)
                } else {
                    submitted[id] ?: denied()
                }
            val version = item.formVersion ?: badSubmission()
            val answers = item.answers ?: badSubmission()
            val form = forms.loadAndValidate(definition.expoId, policy.formId, version, answers)
            if (form.id != policy.formId || form.expoId != definition.expoId || form.purpose != "PROGRAM") invalidPolicy()
            if (version != guardian.formVersion || form.version != guardian.formVersion) conflict()
            validateReferences(guardian, form.questions)
            if (id in guardians &&
                answers.path(guardian.schoolTypeQuestionId.toString()).asString() == guardian.elementarySchoolValue
            ) {
                denied()
            }
            val needsGuardian = needsGuardian(guardian, answers)
            if ((id in students) != needsGuardian) denied()
        }
    }

    override fun validateSingle(
        command: ApplyProgramSessionCommand,
        definition: ProgramSessionDefinition,
        occupation: String?,
    ) {
        val policy = definition.policy ?: return
        val guardian = policy.guardianPolicy ?: return
        if (!guardian.valid()) invalidPolicy()
        if (occupation == "PARENT") conflict()
        val form = forms.loadAndValidate(command.expoId, policy.formId, command.formVersion, command.answers)
        if (command.formVersion != guardian.formVersion || form.version != guardian.formVersion) conflict()
        validateReferences(guardian, form.questions)
        val elementary = command.answers.path(guardian.schoolTypeQuestionId.toString()).asString() == guardian.elementarySchoolValue
        if (elementary != (occupation == "ELEMENTARY_STUDENT")) denied()
        if (needsGuardian(guardian, command.answers)) conflict()
    }

    private fun saved(
        definition: ProgramSessionDefinition,
        id: Long,
    ): StudentGuardianSubmission =
        jdbc
            .query(
                "SELECT form_version, answers_snapshot FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND program_id = ? AND participant_id = ? " +
                    "AND status IN ('CONFIRMED', 'WAITING')",
                { rs, _ -> StudentGuardianSubmission(id, rs.getLong("form_version"), mapper.readTree(rs.getString("answers_snapshot"))) },
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
                id,
            ).singleOrNull() ?: denied()

    private fun needsGuardian(
        policy: ProgramGuardianPolicy,
        answers: JsonNode,
    ): Boolean {
        val school = answers.path(policy.schoolTypeQuestionId.toString())
        if (!school.isString) denied()
        if (school.stringValue() != policy.elementarySchoolValue) return false
        val grade = answers.path(policy.gradeQuestionId.toString())
        if (!grade.isString) denied()
        return (policy.gradeValues[grade.stringValue()] ?: denied()) in 1..3
    }

    private fun validateReferences(
        policy: ProgramGuardianPolicy,
        questions: JsonNode,
    ) {
        val fields = questions.toList()
        val school = fields.singleOrNull { it.path("id").asLong() == policy.schoolTypeQuestionId } ?: invalidPolicy()
        val grade = fields.singleOrNull { it.path("id").asLong() == policy.gradeQuestionId } ?: invalidPolicy()
        val condition = grade.path("otherJson").path("conditional")
        val parent = condition.path("parentIndex")
        val triggers =
            condition.get("triggerValues")?.takeIf { it.isArray }?.toList()
                ?: listOfNotNull(condition.get("triggerValue"))
        if (school.path("formType").asString() != "DROPDOWN" || !school.path("requiredStatus").asBoolean() ||
            school.path("otherJson").hasNonNull("conditional") || !school.path("jsonData").has(policy.elementarySchoolValue) ||
            grade.path("formType").asString() != "DROPDOWN" || !grade.path("requiredStatus").asBoolean() ||
            grade
                .path("jsonData")
                .properties()
                .map { it.key }
                .toSet() != policy.gradeValues.keys ||
            !parent.isIntegralNumber || !parent.canConvertToInt() || parent.asInt() != fields.indexOf(school) ||
            fields.indexOf(school) >= fields.indexOf(grade) ||
            (condition.hasNonNull("triggerValue") && condition.hasNonNull("triggerValues")) ||
            triggers.size != 1 || !triggers.single().isString || triggers.single().stringValue() != policy.elementarySchoolValue
        ) {
            invalidPolicy()
        }
    }

    private fun denied(): Nothing = throw ResponseStatusException(HttpStatus.FORBIDDEN, "학생·보호자 자격 또는 학년이 올바르지 않습니다.")

    private fun badSubmission(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "신규 참가자의 폼 버전과 답변이 필요합니다.")

    private fun conflict(): Nothing = throw ProgramApplicationConflictException("학생·보호자 정책에 맞는 연결과 폼 버전으로 일괄 신청해야 합니다.")

    private fun invalidPolicy(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "보호자 정책의 구조화 학년 문항 참조가 올바르지 않습니다.")
}
