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
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason
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
            denied(ProgramIneligibleReason.GUARDIAN_LINK_INVALID)
        }
        val occupations = gateway.participantOccupations(definition.expoId, query.participants)
        if (students.any { occupations[it] != "ELEMENTARY_STUDENT" } || guardians.any { occupations[it] != "PARENT" }) {
            denied(ProgramIneligibleReason.GUARDIAN_LINK_INVALID)
        }
        if (occupations.values.any { it !in policy.allowedOccupations }) denied(ProgramIneligibleReason.OCCUPATION_NOT_ALLOWED)
        val submitted = query.submissions.associateBy { it.participantId }
        query.participants.forEach { id ->
            val item =
                if (query.phase == StudentGuardianValidationPhase.PROMOTION || id in query.reusedGuardianIds) {
                    saved(definition, id)
                } else {
                    submitted[id] ?: denied(ProgramIneligibleReason.GUARDIAN_LINK_INVALID)
                }
            val version = item.formVersion ?: badSubmission()
            val answers = item.answers ?: badSubmission()
            val form = forms.loadAndValidate(definition.expoId, policy.formId, version, answers)
            if (form.id != policy.formId || form.expoId != definition.expoId || form.purpose != "PROGRAM") invalidPolicy()
            if (version != guardian.formVersion || form.version != guardian.formVersion) conflict()
            validateReferences(guardian, form.questions)
            val school = answers.path(guardian.schoolTypeQuestionId.toString()).asString()
            val elementary = school == guardian.elementarySchoolValue
            val expected = guardian.guardianSchoolValue
            if (id in guardians && (elementary || (expected != null && school != expected))) schoolTypeMismatch()
            val needsGuardian = needsGuardian(guardian, answers)
            // 보호자는 위에서 초등을 걸렀으므로 여기서 남는 것은 학생이다. 초등이면 4학년 이상, 아니면 학교급이 틀렸다.
            if ((id in students) != needsGuardian) {
                if (elementary) denied(ProgramIneligibleReason.GRADE_INVALID) else schoolTypeMismatch()
            }
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
        if (occupation == "PARENT") conflict(ProgramIneligibleReason.GUARDIAN_LINK_INVALID)
        val form = forms.loadAndValidate(command.expoId, policy.formId, command.formVersion, command.answers)
        if (command.formVersion != guardian.formVersion || form.version != guardian.formVersion) conflict()
        validateReferences(guardian, form.questions)
        val school = command.answers.path(guardian.schoolTypeQuestionId.toString()).asString()
        if (school == guardian.guardianSchoolValue) schoolTypeMismatch()
        if ((school == guardian.elementarySchoolValue) != (occupation == "ELEMENTARY_STUDENT")) schoolTypeMismatch()
        if (needsGuardian(guardian, command.answers)) conflict(ProgramIneligibleReason.GUARDIAN_REQUIRED)
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
            ).singleOrNull() ?: denied(ProgramIneligibleReason.GUARDIAN_LINK_INVALID)

    private fun needsGuardian(
        policy: ProgramGuardianPolicy,
        answers: JsonNode,
    ): Boolean {
        val school = answers.path(policy.schoolTypeQuestionId.toString())
        if (!school.isString) schoolTypeMismatch()
        if (school.stringValue() != policy.elementarySchoolValue) return false
        val grade = answers.path(policy.gradeQuestionId.toString())
        if (!grade.isString) denied(ProgramIneligibleReason.GRADE_INVALID)
        return (policy.gradeValues[grade.stringValue()] ?: denied(ProgramIneligibleReason.GRADE_INVALID)) in 1..3
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
            policy.guardianSchoolValue?.let { !school.path("jsonData").has(it) } == true ||
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

    // 403 reason은 ProgramSessionApplicationExceptionHandler가 ineligibleReason으로 옮기는 enum 이름이다(#271).
    // 예외 형식(403 ResponseStatusException, 409 ProgramApplicationConflictException)은 대기 승급의 취소 판정이 쓰므로 그대로 둔다.
    private fun denied(reason: ProgramIneligibleReason): Nothing = throw ResponseStatusException(HttpStatus.FORBIDDEN, reason.name)

    // #270(guardianSchoolValue)의 학교급 거절도 이 함수를 지난다.
    private fun schoolTypeMismatch(): Nothing = denied(ProgramIneligibleReason.SCHOOL_TYPE_MISMATCH)

    private fun badSubmission(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "신규 참가자의 폼 버전과 답변이 필요합니다.")

    private fun conflict(reason: ProgramIneligibleReason? = null): Nothing =
        throw ProgramApplicationConflictException("학생·보호자 정책에 맞는 연결과 폼 버전으로 일괄 신청해야 합니다.", reason)

    private fun invalidPolicy(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "보호자 정책의 구조화 학년 문항 참조가 올바르지 않습니다.")
}
