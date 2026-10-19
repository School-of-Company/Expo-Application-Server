# 취소 대상과 실제 실행자 (#143)

취소 대상(`participantId`, 예약 `reservationId`)과 실제로 취소를 실행한 주체를 따로 기록한다. 실행자 값은 요청 본문에서 받지 않는다.
별도 로그 테이블을 새로 만들지 않고, 기존 원장에 V26 컬럼을 추가해 기록한다.

## 주체 의미

| actorType | 식별자 | 출처 |
| --- | --- | --- |
| `ADMIN` | `actorId` = 관리자 ID (기존 의미 유지). 사전등록은 V32 `actor_id`/`withdrawn_actor_id`(#182) | Expo `APPLICATION_CANCEL` 검증 결과 |
| `USER` | 확정 참가자: `actor_participant_id` = User 대표 참가자 ID | 참가자 세션 → User managed party의 `representative` |
| `USER` | 대기 예약: 예약 자체 `(expo_id, request_id)` + `REPRESENTATIVE` 역할 | 세션 검증 번호와 예약 대표자 번호가 일치하는 기존 소유권 검사 |
| `SYSTEM` | 없음 | 자동 승급과 자동 취소 |
| `UNKNOWN` | 없음 | V21/V26 이전 과거 행, 그리고 인증된 사람 주체가 없는 내부 호출 |

- 본인 취소, 동행자 대리 취소, 학생·보호자 연계 취소에서는 모든 전이에 같은 대표자 참가자 ID를 기록한다. 동행자(취소 대상)를 실행자로 기록하지 않는다.
- 대기 예약 실행자에게는 가짜 User ID를 만들지 않는다. 식별은 예약 단위로만 하며, 여러 예약에 걸친 동일인 ID는 없다.
- 내부 호출 `DELETE /internal/expos/{expoId}/preregister-sessions/{sessionId}/applications`(요청 `representativeId`는 소속 검사용일 뿐 실행자가 아님)와 `POST /internal/expos/{expoId}/preregister-reservations/{requestId}/withdrawal`은 운영자나 서비스의 신원을 전달하지 않는다. 그래서 새 기록은 `UNKNOWN`으로 남긴다. 취소 기능과 HTTP 계약은 바뀌지 않는다.
  - 내부 프로그램 취소 `POST /internal/program-session-applications/{id}/cancellation`은 #156에서 제거했다. 이 경로는 실행 주체를 받지 않았고, 소유자·관리자 취소가 거치는 Attendance 입장·출석 보호와 Expo `APPLICATION_CANCEL` 권한 확인 없이 취소 기간만 확인했다. 같은 기능은 관리자 취소 `POST /application/admin/expos/{expoId}/program-session-applications/{applicationId}/cancellation`으로 제공한다. 같은 컨트롤러의 프로그램 정의 변경과 reconciliation 내부 경로는 유지한다.
  - 이 경로로 남은 과거 기록은 수정하거나 추정하지 않는다. #143 이전 행은 `USER`이면서 `actor_participant_id=NULL`(실행 대표자 미기록)이고, #143부터 제거 전까지의 행은 `UNKNOWN`이다. 신청(APPLIED) 이력의 `USER`도 실행자 참조를 기록하지 않는다.
- 관리자 화면의 사전등록 취소·대기 철회는 `POST /application/admin/expos/{expoId}/preregistrations/cancellation`(#182)이다. 같은 취소·철회 서비스를 쓰고 `ADMIN`과 관리자 ID를 기록한다. 위 내부 경로 두 개는 그대로 `UNKNOWN`이다.
- 과거 행은 대상 참가자나 현재 대표자로부터 역추정하지 않는다. 사전등록 전이 `created_at`은 과거 행이 NULL이고, 새 전이부터 기록한다.
- 재시도와 복구는 기존처럼 잠금 뒤 `CANCELLED`/`withdrawn`를 다시 확인한다. 따라서 전이가 새로 생기지 않고 최초 실행자 기록도 바뀌지 않는다.
- 연락처와 세션 토큰은 실행자 기록에 저장하지 않는다.

## 응답

- 프로그램 이력 `GET /application/admin/expos/{expoId}/program-session-applications/activities`에 `actorUser`를 추가했다. 형식은 `{participantId, reservationRequestId: null, role: REPRESENTATIVE, name, nameSource: CURRENT_USER}`이다. 기존 `participantId`(대상), `actorType`, `actorId`는 그대로다.
- 사전등록 목록, 상세, 대상 선택 행에 `cancelledAt`, `cancellationActorType`, `cancellationActorUser`를 추가했다. #182부터 `ADMIN`이면 `cancellationActorId`에 관리자 ID를 준다. 프로그램 이력의 ID·타입 계약을 깨지 않기 위해 별도 행에 붙였다.
  - 확정 취소: 마지막 `CANCELLED` 전이를 쓴다. 이름은 `CURRENT_USER`다.
  - 대기 철회: `withdrawn_at`과 `withdrawn_actor_type`을 쓴다(NULL은 `UNKNOWN`으로 응답). `USER`이면 `reservationRequestId`와 원본 예약 스냅샷 `user_request.name`을 `REGISTRATION_SNAPSHOT`(신청 당시 이름)으로 제공한다.
- `CURRENT_USER` 이름은 조회 시점에 User `POST /internal/standard-participants/names`로 한 번에 조회한 현재값이며, 처리 당시 이름이 아니다.
  - User는 ID 하나라도 없으면 전체를 404로 거부한다. 이때나 원천 장애·계약 위반 때는 실행자 ID를 유지하고 `name=null`로만 응답한다. 행별 재시도는 하지 않는다.
  - 대상 참가자 `details` 실패는 기존처럼 요청 전체 실패로 남는다.
