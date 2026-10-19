# 사전등록 관리자 접수대장 (#123)

기준: develop `9bc5f8f`, Application [#123](https://github.com/School-of-Company/Expo-Application-Server/issues/123).
기존 프로그램·연수 원장, 신청 정책, 집계, 취소, Report 및 Notification 경로는 유지한다.

## HTTP 계약

모든 경로는 `/application/admin/expos/{expoId}/preregistrations` 아래에 있다.
매 요청 원본 Bearer JWT로 Expo의 해당 행사 `APPLICATION_READ`를 검증한다.
개인정보를 포함하므로 응답은 `Cache-Control: no-store`다.

| 메서드/경로 | 요청 | 의미 |
| --- | --- | --- |
| GET | `sessionId`, `status`, `q`, `page=0`, `size=20` | 목록과 같은 조건의 `totalElements` |
| GET `/detail` | `key` 필수 | 목록의 행 전체 상세, 행사/탭에 없으면 404 |
| POST `/targets` | 아래 SELECTED/FILTERED JSON | 동일 목록 조건의 현재 대상 해석, 최대 5000행 |
| GET `/summary` | 기존 계약 그대로 | 전체 행사·회차별 고유 확정/취소, 대기 인원 및 복구 예약 집계 |
| POST `/cancellation` | `{"keys":[...]}` | 관리자 취소·대기 철회(#182). 아래 "관리자 취소" 참조 |
| GET `/activities` | `action`, `sessionId`, `from`, `until`, `q`, `page=0`, `size=20` | 상태 변경 이력(#189). 아래 "상태 변경 이력" 참조 |

`status`는 CONFIRMED, WAITING, CANCELLED, PENDING의 쉼표 목록이다.
JSON 필터는 단일 상태 문자열 또는 배열을 받는다. `sessionId`는 양수다.
`q`는 1~50자이며 공백만 허용하지 않는다. 확정·취소 신청은 User 현재 이름/번호 검색 결과로 좁힌다.
예약은 제출 당시 대표자 이름(대소문자 무시 부분 일치) 또는 숫자만 정규화한 대표자 번호 부분 일치다.
예약 동행자 이름 검색은 지원하지 않는다. User 검색 의미와 예약 스냅샷 검색 의미는 다르다.
프로그램 탭의 programType/programId/groupId/occupation/eventDate 등은 암묵적으로 가져오지 않는다.
지원하지 않는 query/JSON 필드, 반복·빈 query, 잘못된 페이지는 400이다.
빈 결과는 200과 빈 items/0건, 범위 밖 상세·선택은 404다. User 참가자 누락은 기존 gateway의 502다.

## 행과 인원, 관계, 시간

| key | 상태 | 행 단위 및 원천 |
| --- | --- | --- |
| `APPLICATION:{expoId}:{long id}` | CONFIRMED / CANCELLED | 로컬 사전등록 참가자 신청 1행, applicants=1 |
| `RESERVATION:{expoId}:{requestId}` | WAITING | User 저장 전 일행 1행, applicants=일행 인원 |
| 같은 예약 key | PENDING | RESERVED 복구 중, User 확정 전 좌석 보유. 참가자 ID 추정 금지 |
| 같은 예약 key | CANCELLED | withdrawn_at이 있는 철회된 대기 일행 |

완료된 예약은 참가자 신청과 중복 표시하지 않는다. 일반 REJECTED 저장 실패는 접수 행에 넣지 않는다.
대기는 승급 후 같은 예약 key에서 PENDING이 되고, 확정 완료 시 APPLICATION 행들로 바뀐다.
이전 선택 key가 사라졌으면 404로 재조회하게 한다. 다른 행으로 자동 치환하지 않는다.

`applicationId`는 프로그램 UUID가 아닌 사전등록 BIGINT다. `reservationId`는 행사 안의 원래 예약 requestId다.
예약은 participantId/representativeId가 null이며 QR도 없다.
참가자 행의 로컬 representativeId와 participantId가 같으면 대표자, 다르면 그 대표자의 동행자다.
User 현재 participant 상세는 `participant`에 제공하며 원장 관계를 덮어쓰지 않는다.
예약 `reservation`은 대표자 name/phoneNumber/occupation, 제출 당시 informationJson 및 questions만 노출한다.
동행자는 이 폼 질문과 답변 스냅샷으로 확인한다. 전체 user_request, 동의·내부 복구 명령/결과는 반환하지 않는다.
예약 테이블에는 생성 시간이 없으므로 createdAt=null이다. 참가자 행만 실제 created_at을 제공한다.
현재 저장 회차의 sessionId를 표시하며, 사전등록에 존재하지 않는 제출 당시 회차 제목/일시는 만들지 않는다.

정렬은 sessionId 오름차순 후 key 문자열 오름차순으로 고정한다. 접수 시각/FIFO/접수번호 순서가 아니다.
페이지와 count는 같은 읽기 스냅샷에서 계산하며 기존 행사 공유 lock을 사용한다.
User 호출은 DB 트랜잭션/행사 lock 밖에서 실행한다.
totalElements는 서로 다른 단위의 **행 수**다. 인원 수로 표시하지 않는다.
기존 `/summary`는 필터를 받지 않는 전체 집계이며, 목록의 필터된 행 수와 같다고 해석하지 않는다.
확정 고유 인원과 취소 이력 인원은 중복될 수 있다.

## 선택과 검색 전체

```json
{"kind":"SELECTED","keys":["APPLICATION:00000000-0000-0000-0000-000000000001:12"]}
```

```json
{"kind":"FILTERED","filter":{"sessionId":1,"status":["CONFIRMED","WAITING"],"q":"0101234"}}
```

SELECTED는 중복 없는 keys만, FILTERED는 filter만 받는다.
key 자체에 expoId가 포함되므로 두 행사에서 같은 예약 requestId를 사용해도 대상을 혼동하지 않는다.
FILTERED에서 page/size는 기본값만 허용하며 목록의 페이지를 대상 제한으로 사용하지 않는다.
선택은 현재 행사에서 모든 key가 존재해야 성공한다. 프로그램 UUID나 다른 행사 key가 섞이면 전체 404다.
검색 전체는 목록과 같은 repository/검색 해석을 사용하고 5000행 초과는 422로 실패한다.
이 응답은 저장된 작업/내보내기 스냅샷이 아니며 조회 사이 변경을 고정하지 않는다.
응답 tab은 PREREGISTRATION이다. reportSupported와 messagingSupported는 false다.
외부 계약 확장 전 실제 엑셀 생성/메시지 발송이 성공했다고 표시하면 안 된다.

## 기존 소비자·취소와의 대조

Report의 `/internal/expos/{expoId}/program-session-applications/...`는 프로그램 UUID와
AdminApplicationRow/ReportRegistrationSource를 사용한다. 사전등록 BIGINT/예약 key를 넘기지 않는다.
User 저장 전 예약은 ReportRegistrationSource의 participantId도 만들 수 없다.
Notification 그룹 발송도 기존 AdminGroupMessageTargetResolver의 프로그램 원장 및 UUID만 지원한다.
기존 대표자 번호 정책은 **User 대표자의 phoneNumber**다. 동행자의 번호나 notificationPhoneNumber로 대체하지 않는다.
일반 메시지의 동일 번호 중복 제거, 개인화 메시지의 신청별 대상, fingerprint 재확인 정책은 기존 계약을 따른다.
현재 조회 응답의 participant.phoneNumber/notificationPhoneNumber는 표시용이며 발송 가능 번호 확정이 아니다.
대기 스냅샷 번호가 있어도 발송 가능 상태를 뜻하지 않으며 대기 SMS는 기존 정책대로 범위 밖이다.

기존 공개 관리자 취소는
`POST /application/admin/expos/{expoId}/program-session-applications/{applicationId}/cancellation`이며
APPLICATION_CANCEL, Attendance 관리자 acquire/abort, 마감·입장·스캔 보호를 재사용한다.
하지만 이 경로는 **프로그램 UUID 전용**이다. 사전등록 applicationId/key와 연결된다고 가장하지 않는다.
기존 소유자 사전등록/대기 취소는 사용자 세션 경로이며 관리자 JWT로 우회 호출하지 않는다.
사전등록 관리자 취소는 #182의 `/cancellation`이 맡는다.

## 관리자 취소 (#182)

`POST /application/admin/expos/{expoId}/preregistrations/cancellation`은 목록 key를 그대로 받는다.
`/targets`와 달리 Expo `APPLICATION_CANCEL`(프로그램 관리자 취소와 같은 권한)을 검증한다.

- `APPLICATION` key: 같은 회차·같은 대표자 소속 한 일행만, 최대 5건. 대표자와 동행자를 함께 보낼 수 있다.
  기존 `CancelPreregisterSessionService`를 그대로 쓰므로 Attendance 관리자 acquire/abort, 입장·취소 마감·활성 프로그램 신청·
  동행자 먼저·대표자 나중 순서, 대기 승급이 같다. #198부터 대표자만 보내도 취소되며 동행자 신청은 남는다.
- `RESERVATION` key: WAITING 일행 하나만 철회한다. PENDING(복구 중)은 409다.
- 두 종류 혼합, 여러 일행, 예약 둘 이상은 400이다. 없는·다른 행사 key가 하나라도 있으면 404이며 다른 행으로 치환하지 않는다.
- 이미 취소·철회된 대상은 200으로 끝나고 새 전이를 만들지 않는다. 응답은 `{"keys":[...],"status":"CANCELLED"}`(no-store)다.
- 실행자는 `ADMIN`과 관리자 ID(V32 `tb_preregister_transition.actor_id`, `tb_preregister_reservation.withdrawn_actor_id`)로 기록하고
  목록·상세·대상 행의 `cancellationActorId`로 내려준다. 그 외 주체와 과거 행은 null이다.
- 확인 팝업과 동행자 함께 취소 선택은 Client 몫이다(Expo-Client#664).

## 상태 변경 이력 (#189)

`GET /application/admin/expos/{expoId}/preregistrations/activities`는 `APPLICATION_READ`를 검증하고 `no-store`로 응답한다.
새 로그 테이블 없이 기존 원장에서 읽는다. 행은 시각 역순(`createdAt` 미기록은 맨 뒤, 같으면 `key` 역순)이다.

- `CONFIRMED`/`CANCELLED`: `tb_preregister_transition` 한 행(참가자 단위, `applicants=1`). `previousStatus`는 같은 신청의 앞선 전이
  상태이며 없으면 null이다(최초 전이 또는 앞선 기록이 없는 과거 행). 전이에는 이전 상태 컬럼이 없어 조회 때 계산한다.
- `WAITING_WITHDRAWN`: 철회된 대기 예약(`withdrawn_at`) 한 행(일행 단위, `participantId=null`, `reservationRequestId`).
  `previousStatus=WAITING`, `status=CANCELLED`이고 `reservation.name/phoneNumber`는 제출 당시 대표자 값이다.
- 대기 접수와 승급은 포함하지 않는다. 예약에 접수 시각이 없고, 승급은 확정 전이와 구분할 기록이 없다(추정하지 않는다).
- 실행자 필드는 접수대장 취소 필드와 같은 의미다: `actorType`(과거 행·내부 호출은 `UNKNOWN`), ADMIN의 `actorId`,
  USER의 `actorUser`(확정은 `CURRENT_USER` 이름, 대기는 `REGISTRATION_SNAPSHOT` 이름). `participant`는 조회 시점 User 정보다.
- 필터: `action`, `sessionId`, `from`(포함)/`until`(미포함), `q`. 기간 조건은 시각이 NULL인 과거 행을 제외한다. `q`는 확정·취소는
  User 현재 이름·번호 검색 결과, 대기 철회는 제출 당시 이름·번호다. 지원하지 않거나 반복·빈 query, `from>=until`은 400이다.
- 응답 `ledgerKey`는 목록·상세의 `key`와 같아 Client가 접수대장 행으로 이동할 수 있다.

## 의존 이슈 초안 (아직 GitHub에 생성하지 않음)

### Report: 사전등록 탭의 선택/검색 내보내기 계약

- PREREGISTRATION source와 행사별 APPLICATION BIGINT/RESERVATION requestId를 구분해 수용한다.
- 이 문서의 필터·행/인원 단위를 유지하고, 확정/취소 User 원천과 User 미저장 대기 스냅샷 열을 정의한다.
- PENDING 포함 여부, 개인정보 열/다운로드 권한, 불변 snapshot 및 최대 크기/페이지 소비를 합의한다.
- 다른 행사/프로그램 탭 혼입 거부와 검색 결과·내보내기 대상 일치를 양 서비스 계약 테스트로 검증한다.
- Application의 targets 응답을 기존 Report 성공 계약으로 오인하지 않는다.

### Notification/Application: 사전등록 그룹 발송 source 확장

- 프로그램 UUID 전용 source를 유지하며 별도 PREREGISTRATION source/대상 스키마를 합의한다.
- 우선 User 저장된 CONFIRMED/CANCELLED만 후보로 논의하고 상태별 실제 허용 여부를 확정한다.
- User 대표자 phoneNumber 해석, 행사/탭 scope, 중복 제거, 개인화, preview→execute fingerprint를 재사용한다.
- WAITING/PENDING/대기철회는 미지원으로 명시한다. 대기 SMS를 이번 이슈로 도입하지 않는다.
- 검색 q와 별도 User 내부 토큰 경계(기존 FILTERED q 금지)를 해소할 서비스 간 계약이 필요하다.

사전등록 관리자 취소 초안은 #182로 구현했다(위 "관리자 취소").

## #124 후속 통합

새 AdminPreregistration* 파일과 전용 서비스 구현은 #123 소유다.
기존 AdminParticipantGateway, AdminApplicationQueryDto, 취소·이력 코드는 변경하지 않는다.
`src/test/kotlin/team/startup/application/AdminApplicationHttpTests.kt`에 기존 공급자 스텁을 재사용한 회귀 테스트를 추가하므로
#124의 같은 테스트 파일 변경과 충돌할 수 있다. 양쪽 테스트·스텁을 보존해서 통합한다.
#124가 공통 User gateway/권한/DTO를 바꾸면 기존 인터페이스 호환성과 본 이슈의 검색·누락 응답 회귀를 다시 실행한다.
최신 develop 이후 #123/#124는 독립 병합 가능하지만, 양쪽 병합 뒤 전체 CI가 필요하다. 스키마 변경은 없다.
