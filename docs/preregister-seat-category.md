# 사전등록 좌석 구분: #173

## 공급자 계약 상태 (2026-10-19, develop `4f8512a` 기준)

Expo #144 계약은 Expo 브랜치 `feat/144-preregister-target-capacity` 커밋 `60cfa43`에서 확정됐고, 아직 머지 전이다. 내부 회차 정의와 변경 승인 `definition`에 `teacherCapacity`·`nonTeacherCapacity`(nullable Int)가 있다. 둘은 함께 null이거나, 함께 0 이상이며 합이 `capacity`와 같다. Expo는 DB CHECK로도 이를 강제한다. 박람회 기본값은 Expo 안에서 새 회차 초기화에만 쓰이고 Application으로 전달되지 않는다. 기존 회차는 V40 이후에도 null이다.

- 두 값이 null인 회차는 기존 단일 `capacity`·대기·승급 동작을 그대로 쓴다.
- 짝이 맞지 않거나 합이 `capacity`와 다른 정의는 조회에서 502, 변경 승인에서 400이다(`isValidDefinition`).
- `sameAs`는 두 필드를 포함해 비교한다. 따라서 구분 정원만 바뀌어도 `definitionChanged=true`와 일치한다.
- 변경 승인: 신청 이력이 있어도 `capacity`·`teacherCapacity`·`nonTeacherCapacity`(그리고 `closed`)만 바뀐 정의는 받는다. 단, 같은 배타 잠금에서 전체 정원이 `occupied()` 이상이어야 하고, 구분 정원이면 `교사 ≥ 교사 사용 + 미상`, `그 외 ≥ 그 외 사용 + 미상`이어야 한다. 대기 일행이 남은 회차는 구분 정원으로 바꿀 수 없다. 운영자가 null 회차에 구분 정원을 보완하는 전환도 같은 검사를 따른다. 그 밖의 정의 변경은 기존처럼 409다.
- V31 이전 확정자는 미상이다. 그래서 이력이 많은 기존 회차를 구분 정원으로 전환하려면, 두 구분 정원이 각각 미상 인원 이상이어야 한다. 미상을 추정해 분류하지 않는다.
- 내부 `apply` 경로(직업 정보 없는 신뢰 ID)는 구분 정원 회차의 새 좌석을 409로 거절한다.
- 배포 순서: Application #173(V31) → Expo #144 → Client #643.

## 구분 판정

- `TEACHER`, `PRE_SERVICE_TEACHER`는 `TEACHER`, 그 밖의 직업 코드는 `OTHER`다. 표시 문구로 판정하지 않는다(`PreregisterSeatCategory`).
- 대표자는 신청 스냅샷의 직업 답변, 동행자는 각 동행자 답변이다. 대표자 직업이 없으면 NULL(미상)이다.
- 결정(2026-10-19): 직업이 없는 신청은 정상 상태가 아니다. 구분 정원 회차에서는 대표자 직업이 없으면 User 저장·좌석 확보 전에 400으로 거절한다. 배포 전 운영 사전등록 폼의 직업 질문이 필수인지 확인한다.

## 저장 (V31, develop V29·V30 다음)

- `tb_preregister_reservation.teacher_applicants`/`other_applicants`: 예약 시점 스냅샷의 구분별 인원. `applicants`에서 둘을 뺀 나머지가 미상이다. 대기 예약도 같다.
- `tb_preregister_application.seat_category`: 확정 시 참가자별 구분. 트리거로 변경을 막는다. 개인 취소는 행의 구분을 그대로 두므로 원래 구분의 좌석만 돌아온다.
- 기존 행은 NULL이며 300/1,000 같은 값이나 직업 추정으로 채우지 않는다. V31 이전 예약은 복구·승급 뒤에도 미상으로 확정한다.

## 확정

- User 응답에는 동행자 ID별 답변이 없다. 동행자 구분이 하나뿐이면 그대로 배정한다. 구분이 섞이면 User `POST /internal/standard-participants/details`로 각 ID의 직업을 읽고, 구분별 인원이 예약과 정확히 같아야 확정한다.
- 조회 실패·지연은 불확실한 결과로 본다. User 결과를 저장하고 좌석을 유지한 채 재시도한다. 구분이 다르면 `error_status=502`, 무한 `next_attempt_at`로 운영자 확인을 기다린다(기존 retry 경로 재사용). 어느 경우에도 좌석을 풀거나 ID를 만들지 않는다.

## 대상별 정원 회차의 접수

- 같은 Expo 배타 잠금 안에서 `교사 정원 - 교사 사용 - 미상`과 `그 외 정원 - 그 외 사용 - 미상`이 일행의 구분별 인원을 모두 수용하고, 전체 정원도 남을 때만 예약한다. 하나라도 모자라면 409이며 부분 수용은 없다. 서로의 빈자리를 쓰지 않는다.
- 대표자 직업이 없으면 400이다. 대기는 쓰지 않는다. 대기 일행이 남아 있으면 새 신청은 409이고, 대기 승급은 하지 않는다.
- #169 동행자 추가(develop `917d409`)도 같은 잠금에서 추가하는 동행자의 구분별 인원을 검사한다. 구분 정원 회차에서는 대기 추가를 받지 않는다(`waitRoom = 0`). 예약에는 추가 동행자의 구분 인원을 저장한다. 확정 시 User `participantIds`(새 동행자)에만 구분을 배정하며, 대표자 행은 그대로다.
- 내 신청의 `remainingCompanionSlots`는 기존처럼 전체 빈자리 기준이다. 일행 상한 4명 이하로 잘리고 인증된 확정 소유자에게만 보인다. 실제 구분 판정은 추가 요청 때 한다.
- 사용량에는 확정 행, User 확정 전 RESERVED 예약, 참여 제안(#144)이 잡은 대기 일행이 들어간다. 불확실한 User 결과와 운영자 확인 대기 예약도 좌석을 계속 잡는다.

## 유형별 오픈 시각(#209)

- PRE 폼 응답의 `occupationOpenAt`(Form #164, ISO-8601 offset, nullable) 전에는 교원·예비교사(`TEACHER`/`PRE_SERVICE_TEACHER`)만 새로 신청한다. 일반 사전등록(대표·동행자), 동행자 추가(추가 인원), 다른 회차 추가(참석자)가 `PreregisterSeatCategory.requireOpen` 하나를 쓴다. 직업이 없는 사람도 거절하며, 응답은 400 `{"status":400,"code":"OCCUPATION_NOT_OPEN","message":"교원·예비교사 외 유형은 2026-10-26 08:30(KST)부터 신청할 수 있습니다."}`이다. 그 시각부터는 자동으로 풀린다.
- 필드가 없거나 null이면 제한하지 않으므로 Form보다 먼저 배포해도 된다. 기존 예약의 멱등 재시도·복구는 이 검사 전에 처리한다.
- 신뢰 ID 내부 `apply`는 직업이 없어 검사하지 않는다. 대상별 정원 회차는 이 경로로 좌석을 잡지 않는다(위 #173 규칙).

## 조회

- 공개 `GET .../preregister-sessions/{sessionId}/capacity`는 대상별 정원 회차에서 `remaining`·`maxApplicants`·`confirmed`를 null로 내린다. 합계에서 교사 숫자를 거꾸로 계산할 수 있기 때문이다. 대신 `targetSeats {teacherAvailable, nonTeacherRemaining, nonTeacherMaxApplicants, ownTeacherRemaining}`를 내린다. 교사 쪽은 1석 이상 남았는지만 알린다.
- `PARTICIPANT_SESSION` 쿠키로 확인한 일행에 이 회차의 확정 교사 좌석이 있으면 `ownTeacherRemaining`을 채운다. 만료·무효 세션은 공개 응답으로 처리하고, 그 밖의 User 오류는 그대로 돌려준다. Client CSS 숨김에 의존하지 않는다.
- 관리자 `GET .../preregister-summary`의 각 회차에 `seatUsage {teacher, other, unknown}`을 추가했다. 총합은 `occupied()`와 같다.

## 남은 연동

- Expo #144 머지 뒤 실제 연동 확인. 공개 회차 정의 응답(`PublicPreregisterSessionResponse`)에 교사 숫자가 없는지 Expo에서 확인한다.
- Client #643: 대상별 회차의 null 숫자와 `targetSeats`를 소비한다. 소유자 교사 잔여석을 받으려면 capacity GET을 `credentials: 'include'`로 보낸다. Gateway는 변경이 필요 없다(2026-10-19 확인: 해당 GET은 Config `gateway-{dev,prod}.yml`의 `publicPaths`에 있고, 프록시는 `Cookie`를 지우지 않으며, CORS는 허용 Origin에 credentials를 허용한다).
- 배포 전 운영 사전등록 폼의 직업 문항이 필수인지 확인한다.
