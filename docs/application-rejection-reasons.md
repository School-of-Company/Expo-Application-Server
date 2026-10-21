# 참가자 신청 거절 사유 코드(#271)

HTTP 상태와 기존 `code`는 바꾸지 않고, 원인을 구분하는 값만 본문에 더한다. 본문에는 참가자 답변·전화번호·내부 문구를 싣지 않는다.

## 프로그램 신청(단건·학생/보호자 일괄)

`ProgramSessionApplicationExceptionHandler`의 `{status, code, ineligibleReason}`을 그대로 쓴다. 보호자 동반 정책 거절은 `StudentGuardianEligibilityAdapter`가 판정한다.

| 상황 | 응답 |
| --- | --- |
| 일괄: 학생이 초등 4학년 이상, 학년 답변 없음·알 수 없는 학년 키 | 403 `PARTICIPANT_INELIGIBLE` + `GRADE_INVALID` |
| 일괄: 보호자가 학교급에서 초등 또는 `guardianSchoolValue`가 아닌 값을 고름, 학생이 초등이 아닌 학교급을 고름 / 단건: 학교급 답변과 초등 직업이 어긋남, `guardianSchoolValue`를 고름 | 403 `PARTICIPANT_INELIGIBLE` + `SCHOOL_TYPE_MISMATCH` |
| 일괄: 연결 구성 오류, 학생 직업이 `ELEMENTARY_STUDENT`가 아니거나 보호자 직업이 `PARENT`가 아님, 신규 참가자 답변 누락, 재사용 보호자의 기존 신청 없음 | 403 `PARTICIPANT_INELIGIBLE` + `GUARDIAN_LINK_INVALID` |
| 일괄: 회차 허용 직업 밖 참가자 | 403 `PARTICIPANT_INELIGIBLE` + `OCCUPATION_NOT_ALLOWED` |
| 단건: 초등 1~3학년 단독 신청 | 409 `APPLICATION_CONFLICT` + `GUARDIAN_REQUIRED` |
| 단건: 보호자(`PARENT`) 단독 신청 | 409 `APPLICATION_CONFLICT` + `GUARDIAN_LINK_INVALID` |
| 정책 꺼짐 회차의 일괄 신청, 보호자 정책 폼 버전 불일치 | 409 `APPLICATION_CONFLICT`, `ineligibleReason: null`(참가자가 고칠 사유가 아님) |

- 403 사유는 `ResponseStatusException` reason에 enum 이름을 싣고 핸들러가 옮긴다. 409는 `ProgramApplicationConflictException.ineligibleReason`이다.
- 예외 형식은 그대로다. 대기 승급(`StudentGuardianBatchPromotion`)은 4xx `ResponseStatusException`을 `ELIGIBILITY_403`, 충돌 예외를 `ELIGIBILITY_CONFLICT`로 취소하므로 승급 결과는 바뀌지 않는다.
- Client(Expo-Client #790)는 `ineligibleReason`을 `code`보다 먼저 읽어 `GUARDIAN_REQUIRED`·`GRADE_INVALID`·`SCHOOL_TYPE_MISMATCH` 문구를 고른다. `GUARDIAN_LINK_INVALID` 문구는 아직 없어 상태별 공통 문구가 나온다.

## 내 신청 현황(사전등록 동행자·회차 추가)

`OwnerApplicationSessionExceptionHandler`가 `OwnerApplicationRejectedException`을 `{status, code}`로 내린다. 다른 오류 본문은 그대로다.

- 동행자 추가 `POST …/my-applications/PREREGISTER/{applicationId}/companions`: `PreregisterCompanionPolicy`의 차단 판정을 조회의 `companionAddBlockedReason`과 같은 code로 돌려준다. 일행 5명(대표자 포함)이나 폼 동행자 상한을 넘기면 409 `COMPANION_LIMIT_REACHED`, 남은 자리보다 많이 보내면 원인에 따라 `COMPANION_LIMIT_REACHED` 또는 `SEATS_UNAVAILABLE`이다. 다른 차단(`SESSION_CLOSED`, `NOT_REPRESENTATIVE_PREREGISTRATION` 404, `EXTERNAL_CONTRACT_REQUIRED` 503 등)도 상태는 그대로 code만 붙는다.
- 다른 회차 추가 `POST …/my-applications/preregister-sessions`: 학생만 골라도 받는다(#287). 이 경로에 거절 사유 code는 없다.

## 통합 지점

- #270(PR #274, 머지됨): 보호자 학교급 선택지 `guardianSchoolValue` 거절(일괄 보호자가 그 값을 고르지 않음, 단건 신청자가 그 값을 고름)도 `schoolTypeMismatch()`를 지나 `SCHOOL_TYPE_MISMATCH`가 된다. 값이 없는 기존 정책은 초등 선택만 거절한다.
- #268(정책 revision): 승급 재검증을 건너뛰는 조건은 `StudentGuardianBatchPromotion`에 있고 어댑터 예외 형식을 바꾸지 않으므로 이 변경과 독립이다. 재검증하는 경우의 취소 사유 문자열도 그대로다.
- #267(관리자 경로 사유 전달)은 별도 코드 경로다.
