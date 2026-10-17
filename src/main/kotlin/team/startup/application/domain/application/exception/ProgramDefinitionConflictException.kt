package team.startup.application.domain.application.exception

import team.startup.application.domain.application.presentation.dto.ProgramSessionType

/** Expo가 준 현재 회차 정의가 로컬 승인 정의(current/pending)와 다르다(#163). */
class ProgramDefinitionMismatchException(
    val programType: ProgramSessionType,
    val programId: Long,
) : ProgramApplicationConflictException("프로그램 정책 변경 중이거나 승인되지 않은 정의입니다.")

/** Expo 현재 회차 페이지의 definitionVersion이 바뀌어 첫 페이지부터 다시 읽어야 한다(#163). */
class ProgramDefinitionVersionChangedException : ProgramApplicationConflictException("현재 회차 정의 버전이 변경되었습니다.")
