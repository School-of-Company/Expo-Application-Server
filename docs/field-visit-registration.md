# 참가자 × KST 방문일 현장등록 (#311)

Application이 방문일 원장·생성·조회와 공통 계약을 소유한다. 취소 실행·스캔 guard·durable cancellation workflow는 #310 소유다. V41을 먼저 적용하고 #310은 V42부터 사용한다. 운영 적용·서버 간 실제 통합·SMS 발송은 이 로컬 구현 검증에 포함하지 않는다.

## 공개 계약

`GET /application/expos/{expoId}/field-visits`는 참가자 세션 쿠키로 소유 일행을 확인하고 오늘(KST)의 참가자별 `preregistered`, `fieldVisit`, `legacyFieldDateUnknown`을 돌려준다. 응답은 `no-store`이며 CSRF 토큰을 제공한다. 과거 소유 참가자가 5명을 넘을 수 있으므로 소유 목록 길이를 활성 인원으로 해석하지 않는다.

`POST` 같은 경로는 세션·CSRF·`Idempotency-Key`가 필수다. 요청 예:

```json
{
  "participantIds": [101, 102],
  "newParticipants": [
    {"clientReference":"new-1","name":"새사람","occupation":"GENERAL","region":"GWANGJU"}
  ],
  "personalInformationStatus": true
}
```

기존 사람은 ID로 선택한다. 이 경로에서 기존 사람의 이름·유형·학교·QR을 저장하거나 PRE를 FIELD로 바꾸지 않는다. 새 사람은 별도의 명시 생성 요청으로 User에 보낸다. 신규 항목의 선택 `informationJson`은 JSON 객체 문자열(기본 `{}`)이며, Application이 조회한 FIELD 폼의 ID·문항 스냅샷과 함께 처음 요청에 고정하여 User에 전달한다. 복구가 현재 폼을 다시 덮어쓰지 않는다. 신규 `clientReference`와 응답 ID가 일대일로 정확히 일치해야 한다. 누락·중복·기존 선택 ID를 신규로 돌려준 응답은 성공 처리하지 않는다. 최초 일행은 User가 신규 배열의 첫 사람을 대표자로 만들고, 기존 일행은 기존 대표자 아래 새 사람만 더한다.

응답 200은 `visitDate`와 모든 선택 사람의 결과를 포함한다. `REUSED_PRE`, `REUSED_FIELD`, `REGISTERED_FIELD`를 사람별로 구분하고 FIELD는 원장 DTO를 포함한다. 오늘 유효 PRE는 FIELD를 만들지 않는다. 오늘 FIELD는 같은 UUID·세대를 재사용하며 다른 날짜는 별도 원장이다. 새 동행자는 `clientReference`를 함께 돌려준다. 기존 5명의 재방문은 신규 사람이 아니며, 같은 사람의 여러 날짜를 일행 인원에 더하지 않는다. 현재 활성 사람과 이번 선택의 합집합이 5명을 넘으면 409다. User도 전화번호 잠금 아래 활성·pending 사람 상한을 검증한다.

## 원장·동시성·복구

V41 `tb_field_visit_registration`은 `(expo_id, participant_id, visit_date)` 유일 제약을 가진다. UUID는 해당 날짜에 유지한다. 취소 완료 뒤 재등록은 같은 UUID의 `generation`을 증가시키고 현재 취소 컬럼을 비우되 `tb_field_visit_event`의 이전 REGISTERED/CANCELLED 이력을 보존한다. 이벤트의 `actor_participant_id`와 DTO `cancelledBy`는 참가자 실행자 ID이며 관리자 `actorId`가 아니다.

요청은 전화번호의 세션 advisory lock으로 직렬화한다. HTTP 중에는 DB 트랜잭션을 열지 않는다. 로컬 원장 반영은 Expo 잠금(34) → 정렬된 참가자 잠금(43, 대표자 포함) → 방문일 잠금(74) 및 원장 row lock을 사용한다. PRE·기존 FIELD와 활성 인원을 잠금 안에서 재판정한다. 프로그램 신청도 참가자 잠금 뒤 오늘 FIELD 유효성·취소 pending을 다시 확인한다.

`tb_field_visit_request`에 원래 날짜·정규화 전화번호·입력·소유 일행 스냅샷을 먼저 저장한다. User 신규 생성은 항상 같은 expo/requestId로 재전송하며 legacy `registration-status`는 사용하지 않는다. 정확한 User 결과와 원장 결과 계획을 저장하고 Attendance·User projection을 모두 확인한 뒤 receipt를 확정한다. projection 전의 ACTIVE는 `deliveryPending=true`라 Application 자격·취소를 열지 않는다. 인원 count도 기존 per-participant 멱등 PUT으로 재전송한다.

연결 실패·타임아웃·잘못된 성공 응답은 원래 요청을 유지하고 다른 키로 우회하지 못하게 한다. 같은 키 재시도와 recovery scheduler가 원래 날짜·User requestId·결과 계획을 재사용한다. scheduler는 60초 간격, 40회 실패 뒤 자동 재시도를 멈추며 원장과 미완료 요청을 보존한다. 운영자는 원천을 먼저 바로잡고 같은 공개 요청 키로 재시도해야 한다. ID·방문일을 임의 수정하거나 모호한 요청을 삭제해 성공으로 만들지 않는다. 기존 PRE 좌석 예약·불확실 복구 경로는 그대로 유지한다.

## #310 취소 계약

공통 repository는 `lock(id): FieldVisitRegistration?`, `beginCancellation(id,generation,operationId): Boolean`, `cancel(id,generation,actorParticipantId,operationId): Boolean`, `clearCancellation(id,generation,operationId): Boolean`을 제공한다. #310이 같은 로컬 트랜잭션에서 CAS·이력과 durable delivery를 기록한다. `cancel`은 pending을 유지한다. Attendance/User 둘 다 ack한 뒤 또는 abort가 확정된 뒤에만 해당 세대·operation의 pending을 지운다. 그동안 재활성화·프로그램 접수는 409다. 전달 미완료 등록도 취소 대상이 아니다.

취소 요청의 expected generation은 필수다. 이전 세대의 취소·abort·commit 지연 재시도가 재등록 세대를 건드리면 안 된다. 대상 방문일 첫 입장은 Attendance가 판정하며 전날 입장이나 User 누적 entered를 사용하지 않는다. 대상 날짜에 입장 후 퇴장했어도 취소 불가다. 다른 날짜 FIELD와 유효 PRE는 보존한다.

## 공급자 실행 작업

- [User #202](https://github.com/School-of-Company/Expo-User-Server/issues/202): `POST /internal/expos/{expoId}/field-visit-participants` 명시 생성·exact mapping·같은 키 결과 재현, 방문 projection·활성 5명·ownership 이력 보존. `managed/select`는 inactive/pending도 소유권 확인에 반환하고 `applicationType` 프로필은 유지한다. 모두 inactive여도 소유 대표자가 있으면 `registered=true`다.
- [Attendance #204](https://github.com/School-of-Company/Expo-Attendance-Server/issues/204): 오늘 등록 권위·첫입장 이력, 날짜별 cancellation-status/acquire/commit/abort와 스캔의 원자 fence. 기존 QR을 유지한다.
- [Report #92](https://github.com/School-of-Company/Expo-Report-Server/issues/92): 관리자 원장·엑셀 전체/선택/필터 및 날짜별 인원에 방문일 FIELD 원천을 결합한다. CLOSED #88의 프로필/열 정리와 구분한다.

User와 Attendance 공통 projection은 `PUT /internal/expos/{expoId}/participants/{participantId}/field-visits/{registrationId}`에 `{visitDate,generation,status,operationId}`를 받고 정확히 204를 반환한다. 같은 세대 CANCELLED는 terminal이고, 과거 세대 지연 요청은 새 세대를 바꾸지 않고 acknowledge한다. 404/405/501은 미지원 503으로 닫고 200을 projection 성공으로 보지 않는다. 원래 QR 재사용·재방문은 새 QR이나 문자 이벤트를 만들지 않는다. 신규 사람만 User의 신규 QR 이벤트 정책을 사용한다. 취소 문자 Application#308/Notification#110은 별도 계약이며 이 작업이 발송을 구현했다고 가정하지 않는다.

## Report·관리자 원천

내부 토큰이 필요한 `GET /internal/expos/{expoId}/field-visits`는 `visitDate`(선택), UUID `cursor`, `size`(1~500)를 받는다. `{items,events,nextCursor}`를 반환하며 각 페이지 events에는 해당 items의 모든 generation 이력이 포함된다. 날짜 생략은 모든 명시된 방문일이다. 취소·등록 전달 pending을 확정·입장 완료로 집계하지 않는다. 날짜별 참가자 수와 활성 일행 수·실제 입장 수는 서로 다른 값이다.

## 이관·호환·배포 순서

기존 User FIELD, 생성 시각, 과거 방문일 미기록 자료로 오늘 등록을 추정하지 않는다. V41 backfill은 없다. 옛 FIELD는 미상으로 남고 오늘 권한이 필요하면 세션 소유자가 명시적으로 오늘 등록을 요청한다. 기존 QR·participantId·프로필은 보존한다.

1. V41 및 읽기 원천을 먼저 배포한다. `APPLICATION_FIELD_VISITS_ENABLED=false`가 기본이다. 이때 기존 등록·자격 계약은 유지된다.
2. User#202와 Attendance#204를 배포하고 같은 세대 terminal·과거 세대 fence·신규 exact mapping·날짜별 첫입장/취소 경합을 실제 서버 조합으로 확인한다.
3. #310 V42 취소 소비와 Report#92를 준비한다. Report 새 전체 원장은 Application source가 없으면 누락 성공 대신 실패한다.
4. Client#860의 선택/신규 구분·결과 소비, Gateway 익명 라우팅·세션/CSRF 전달, Expo/User/Attendance/Report 토큰·URL을 확인한 뒤 서버 flag를 활성화한다. 등록 POST enablement와 Attendance delivery 설정도 켠다. Client 코드는 이 작업에서 수정하지 않는다.

flag가 켜지면 기존 `/application/field/standard/{expoId}`는 409 `FIELD_VISIT_SELECTION_REQUIRED`로 닫고 새 경로로 전환한다. 구버전 Application으로 롤백하면 날짜별 자격/취소를 소비하지 못하므로 flag를 끄는 것만으로 호환됐다고 판단하지 않는다. 데이터와 provider projection을 보존한 채 등록 트래픽을 닫고 배포 버전을 함께 맞춘다. 운영 DB 이관·배포·실제 QR 스캔·SMS는 별도 승인된 rollout 검증이 필요하다.

## 로컬 검증

`FieldVisitContractTests`는 실제 HTTP fake provider로 토큰, 신규 exact mapping, 프로필 저장 미호출, 방문일/세대/operation 재현, 미구현·비정상 projection 거절을 확인한다. `FieldVisitWorkflowTests`는 혼합 일행·5명 재방문·소유권·취소 pending·receipt 재현을 확인한다. `FieldVisitHttpTests`는 쿠키/CSRF/멱등키 및 내부 토큰/페이지 상한을 검증한다. `FieldVisitEligibilityTests`는 KST 자정과 legacy fallback·pending을 확인한다. `FieldVisitPersistenceTests`는 PostgreSQL 유일성/동시 등록·세대 CAS/이력·KST 날짜를 검증하며 Docker가 없으면 실행되지 않는다.

모든 Gradle 실행은 `/tmp/expo-application-gradle.lock`을 부모 Perl `Fcntl LOCK_EX`로 잡고 자식 종료까지 유지한다. `nice -n10`, `--no-daemon --max-workers=1 --no-parallel`, in-process Kotlin, Gradle heap 1024m/CPU2, Test fork1/heap512m/CPU2/context cache4 제약을 따른다. 상시 서버·compose는 시작하지 않는다.

2026-10-22 최종 Application 검증: `build ktlintCheck spotlessCheck`를 명시적 test filter와 함께 실행하여 11개 클래스 41건 중 38건 통과, PostgreSQL 3건 Docker 부재로 미실행, 실패 0건이다. 필터는 `FieldVisit*Tests`, `RegistrationIntegrationContractTests`, `OwnerApplicationHttpTests`, `ManagedParticipantContractTests`, `ManagedParticipantCookieHttpTests`, `ProgramSessionPublicHttpTests`, `OutboundInternalTokenConfigTests`다. `git diff --check`도 통과했다. 전체 Docker 의존 suite와 실제 3개 공급자 조합·운영 스캔/SMS는 검증하지 않았다. 기준 HEAD와 develop 공통 조상은 `b686aab`이며 커밋·push·PR·배포는 하지 않았다.

연계 이슈 담당자는 `cfcromn`이며 다음 별도 Orca workspace에 로컬 변경을 남겼다. 중복 생성된 contract/ledger workspace는 구현을 중단하고 기존 변경을 보존했다.

| 서버 | 단일 구현 workspace | 로컬 검증 |
| --- | --- | --- |
| User #202 | `feat-202-field-visit-projection` | 기존 service/util 132건 및 최종 snapshot 관련 21건 통과, ktlint/spotless/assemble 통과. PostgreSQL 5건은 컴파일만 확인 |
| Attendance #204 | `feat-204-field-visit-authority` | 24개 suite 292건 통과, ktlint/spotless/assemble 통과. 새 PostgreSQL 2건과 Testcontainers 통합은 실행하지 못함 |
| Report #92 | `feat-92-field-visit-ledger-excel` | 13개 suite 138건 및 최종 원장 11건 통과, ktlint/spotless/assemble 통과. 생성 XLSX ZIP 무결성과 5개 시트 확인 |

User와 Attendance는 각각 V24 migration을 추가했다. Attendance의 불확실 입장 요청은 독립 scan fence에 남아 시간 경과로 취소 가능 상태가 되지 않는다. 각 서버의 README, `docs/field-visits.md`, `docs/issue-92-handoff.md`에 공급자 계약과 미검증을 기록했다. 이 수치는 중복되는 재검증을 포함하므로 합산하지 않는다. 서버 간 실제 DB·HTTP 조합 검증과 운영 rollout은 미완료이며 기능 flag는 기본 false다.
