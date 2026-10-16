package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.StudentGuardianEligibilityQuery

/** Expo #125의 정책과 구조화 학년·보호자 자격을 최종 제출 및 승급 시 검증한다. */
interface StudentGuardianEligibilityProvider {
    // All linked students must require this policy; all guardians must be eligible companions, not merely representatives.
    fun validate(query: StudentGuardianEligibilityQuery)

    // Reject single submissions that would bypass an enabled guardian policy. Policy OFF keeps existing eligibility.
    fun validateSingle(
        command: ApplyProgramSessionCommand,
        definition: ProgramSessionDefinition,
        occupation: String?,
    )
}
