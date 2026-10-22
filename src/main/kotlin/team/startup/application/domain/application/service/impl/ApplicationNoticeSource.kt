package team.startup.application.domain.application.service.impl

import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import java.util.UUID

// #308: 신청 결과 문자에 넣을 외부 사실(User·Expo)을 읽는 경계. 테스트는 가짜로 바꾼다.
interface ApplicationNoticeSource {
    /** [participantId]의 대표자 번호(없을 수 있다)와 대상자 본인 이름. */
    fun contact(
        expoId: UUID,
        participantId: Long,
    ): NoticeContact

    fun programNotice(
        expoId: UUID,
        type: ProgramSessionType,
        programId: Long,
    ): ProgramPromotionGateway.ProgramNotice
}

data class NoticeContact(
    val representativePhone: String?,
    val name: String,
)
