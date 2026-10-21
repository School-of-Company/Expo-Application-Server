# 사전등록 원장 Report 원천 (#284)

Report가 사전등록(PRE) 원장 XLSX를 만들 때 읽는 계약이다. 행·key·필터 의미는 접수대장(`admin-preregistration-ledger.md`)과 같고,
저장·커서·만료 규칙은 프로그램 report-targets(V12 `tb_report_application_snapshot`)를 그대로 쓴다.
회차 없는 FIELD 등록은 User가 원천이라 이 계약에 넣지 않는다. 가짜 회차 행을 만들지 않는다.

## 경로

`/internal/expos/{expoId}/preregistrations/report-targets`(X-Internal-Token)와
`/application/admin/expos/{expoId}/preregistrations/report-targets`(원본 Bearer JWT, Expo `APPLICATION_READ`) 아래에 있다.

| 메서드 | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| POST | `/selection` | `{"keys":[...]}` 접수대장 key 1–5000, 중복 금지 | `{items, totalElements}` |
| POST | (루트) | `{"filter":{"sessionId","status","q"},"limit":1–500(기본 100)}` | 스냅샷 첫 페이지 |
| GET | (루트) `?cursor=` | 이전 `nextCursor`만 | 다음 페이지 |

- 선택은 key가 하나라도 없거나 다른 행사·프로그램 UUID면 전체 404다. 필터 전체와 선택은 같은 reader와 같은 행을 쓴다.
- 필터 전체는 최대 5000행이며 넘으면 422로 저장하지 않는다. 삭제된 행사 409, User 참가자 누락·잘못된 응답 502, User 장애 503.
  어느 실패도 빈 성공이나 부분 스냅샷으로 바꾸지 않는다.
- 스냅샷 응답: `snapshotId, snapshotAt, expiresAt(15분), totalElements, items, nextCursor`. 만료 410, 다른 행사 404,
  잘못된·정렬되지 않은 cursor 400이다. V39 `source`로 구분하므로 프로그램 cursor로 사전등록 스냅샷을(반대도) 읽으면 404다.
- 소비자는 모든 페이지 건수의 합이 `totalElements`이고 `key`가 중복되지 않는지 확인해 누락·중복을 탐지한다.
  행 집합과 원장 값은 생성 시 한 REPEATABLE READ 읽기에서 고정되고, 이후 승급·취소·신규 신청은 다음 스냅샷에만 보인다.

## 행

| `rowType` | 단위 | 식별자 |
| --- | --- | --- |
| `APPLICATION` | User 참가자 × 회차 1행(CONFIRMED/CANCELLED) | `applicationId`(BIGINT), `participantId`, `representativeId`, `representative` |
| `RESERVATION` | User 미저장 예약 일행 × 회차 1행(WAITING/PENDING/철회 CANCELLED) | `reservationId`(requestId), `participantId=null` |

- 같은 사람의 오전·오후 신청은 별도 APPLICATION 행이다. 회차마다 상태가 다르다.
- 예약 행은 QR 대상이 아니다. `reservationId`를 참가자 ID로 쓰지 않는다. `representativeId`는 동행자 추가 예약
  (`reservationKind=COMPANION_ADDITION`)의 기존 대표자만 있고 그 외 null이다. 일행 대표와 동행자는 `registrationSnapshot`에 있다.
  `reservationKind`는 `PARTY`, `COMPANION_ADDITION`, `SESSION_ADDITION` 중 하나다.
- 좌석: APPLICATION은 `seatCategory` TEACHER/OTHER/UNRECORDED(V31 이전·직업 없음). RESERVATION은
  `seatCounts{teacher, other, unrecorded}`이며 구분 미기록 예약은 전원 unrecorded다.
- `session`: 현재 저장된 회차 정의(title, startedAt, endedAt, place, capacity, teacherCapacity, nonTeacherCapacity, deleted).
  제출 당시 정의가 아니다. 정의에 없는 값은 null이고, teacherCapacity가 null이면 구분 정원 없는 회차다.
- 시각: `createdAt`은 APPLICATION 원장 행 생성(확정) 시각이다. 예약에는 접수 시각 컬럼이 없어 null이다.
  취소는 접수대장과 같은 `cancelledAt`과 `cancellationActor*` 필드다.
- `history`: APPLICATION 행의 `tb_preregister_transition` 전이(오래된 순: status, createdAt, actorType, actorParticipantId, actorId).
  과거 미기록 시각은 null이다. 예약 행은 빈 배열이다.
- `promotedFromWaiting`: APPLICATION은 연결 예약이 대기열을 거쳤는지이며, 연결 예약이 없는 과거 행은 null이다.
  RESERVATION은 대기열을 거쳐 복구 중(PENDING)일 때만 true다.
- `registrationSnapshot`: 신청 당시 일행 제출본(name, phoneNumber, occupation, informationJson, questions)이다.
  동행자 행도 대표 제출본이며, 연결 예약이 없는 과거 APPLICATION은 null이다. 동의값·User requestId·복구 명령은 내보내지 않는다.
- `participant`: 조회 시점의 User 현재 프로필이다. 등록 시점 값(`registrationSnapshot`)과 구분하며 예약 행은 null이다.
