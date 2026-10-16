package team.startup.application.domain.application.presentation.dto

data class ProgramGuardianPolicy(
    val formVersion: Long,
    val schoolTypeQuestionId: Long,
    val elementarySchoolValue: String,
    val gradeQuestionId: Long,
    val gradeValues: Map<String, Int>,
) {
    fun valid(): Boolean =
        formVersion > 0 && schoolTypeQuestionId > 0 && gradeQuestionId > 0 && schoolTypeQuestionId != gradeQuestionId &&
            elementarySchoolValue.isNotBlank() && gradeValues.size == 6 && gradeValues.keys.none { it.isBlank() } &&
            gradeValues.values.toSet() == (1..6).toSet()
}
