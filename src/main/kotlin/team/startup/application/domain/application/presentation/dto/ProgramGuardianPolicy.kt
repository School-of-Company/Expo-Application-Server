package team.startup.application.domain.application.presentation.dto

data class ProgramGuardianPolicy(
    val formVersion: Long,
    val schoolTypeQuestionId: Long,
    val elementarySchoolValue: String,
    val gradeQuestionId: Long,
    val gradeValues: Map<String, Int>,
    // null은 #270 이전 정책이며 보호자 학교급을 검사하지 않는다.
    val guardianSchoolValue: String? = null,
) {
    fun valid(): Boolean =
        formVersion > 0 && schoolTypeQuestionId > 0 && gradeQuestionId > 0 && schoolTypeQuestionId != gradeQuestionId &&
            elementarySchoolValue.isNotBlank() && gradeValues.size == 6 && gradeValues.keys.none { it.isBlank() } &&
            gradeValues.values.toSet() == (1..6).toSet() &&
            (guardianSchoolValue == null || (guardianSchoolValue.isNotBlank() && guardianSchoolValue != elementarySchoolValue))
}
