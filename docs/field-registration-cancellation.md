# 방문일별 FIELD 자체 취소 (#310)

## API와 판정

`GET /application/expos/{expoId}/my-applications`의 `fieldRegistrations`는 #311의
방문일 원장을 소비한다. `registrationId`, `participantId`, `visitDate`, `generation`,
`status`, 등록/취소 시각, `cancelledBy`(세션으로 확인한 대표 참가자 ID),
`cancellable`, `cancellationBlockedReason`를 반환한다. 프로그램/사전등록 목록과 구분하며
가짜 회차 ID를 만들지 않는다. 취소 이력은 #311의 `tb_field_visit_event`에 남는다.

`POST /application/expos/{expoId}/my-applications/FIELD/{registrationId}/cancellation?generation=N`
은 기존 참가자 세션 쿠키와 CSRF 보호를 사용한다. 원장의 참가자가 세션 일행에 속하는지
확인한다. UUID와 세대가 모두 일치해야 하며, 취소 후 재등록한 같은 UUID에 이전 세대
요청을 다시 보내면 `409 FIELD_GENERATION_CHANGED`다. 같은 세대의 완료된 취소는 204다.
`generation` 누락/양수 아닌 값은 `400 FIELD_GENERATION_REQUIRED`다.

취소 기준은 **대상 방문일(KST)의 첫 입장 전**이다. 행사 시작 시각이나 User의 누적
`entered`로 막지 않는다. 전날 입장과 무관하며 대상 날짜 입장 후 퇴장해도 취소 불가다.
Attendance의 날짜별 권위 조회와 스캔을 직렬화하는 acquire가 최종 판단한다.

조회 차단 사유는 `ENTERED`, `ATTENDED`, `CANCELLATION_IN_PROGRESS`,
`REGISTRATION_IN_PROGRESS`, `ACTIVE_PROGRAMS`, `COMPANIONS_ACTIVE`,
`PARTICIPANT_STATE_CHANGED`, `OWNERSHIP_CHANGED`, `APPLICATION_STATE_CHANGED`,
`CANCELLED`, `EXTERNAL_CONTRACT_REQUIRED`다. 실행 중 공급자 409는
`FIELD_CANCELLATION_CONFLICT`로 전달한다. 프로그램/연수 신청은 제출 당시 시작 시각의
KST 방문일로 확인하고, 대표자의 동행자는 같은 날짜의 ACTIVE FIELD/확정 PRE로 확인한다.
날짜를 확인할 수 없는 활성 레거시 프로그램 신청은 보수적으로 차단한다.

## 원자성과 복구

1. 원장 UUID별 세션 advisory lock으로 취소/복구를 직렬화한다.
2. 짧은 로컬 트랜잭션에서 박람회 → 참가자 → 연결 연수자 잠금을 잡고 보호 조건을
   검사한다. 원장 `beginCancellation`과 V42의 `ACQUIRING`을 함께 저장한다.
3. 트랜잭션 밖에서 날짜별 Attendance acquire를 호출하고 User 소유권/연결을 재확인한다.
4. 같은 잠금으로 보호 조건을 다시 검사하고 #311의 generation/operation CAS `cancel`,
   취소 이력, V42 `COMMIT` 결정을 같은 트랜잭션에 기록한다.
5. Attendance commit, User CANCELLED projection을 차례로 전달한다. 둘 다 204를
   확인한 뒤에만 원장의 cancellation pending과 V42 복구 행을 함께 해제한다.

불확실한 acquire는 durable ABORT와 같은 operationId로 복구하며, abort 응답 유실 중에는
pending을 유지한다. 로컬 취소가 커밋됐으면 COMMIT을 ABORT로 바꾸지 않는다. commit이나
User projection 응답이 유실돼도 같은 ID/방문일/세대/operationId로 재전달한다.
공급자 409를 취소 성공으로 간주하거나 participant 전체 취소로 대체하지 않는다.
기존 취소 복구 스케줄러가 V42도 처리한다. pending 동안 #311 재활성화는 닫힌다.

다른 PRE나 다른 날짜 FIELD는 수정하지 않는다. QR/새 프로그램 자격/활성 일행5명은
#311과 User/Attendance의 날짜별 등록 projection을 함께 배포해야 일치한다. 관리용
소유권 조회는 취소 이력 재시도/재등록 ID 선택을 위해 비활성 참가자도 확인할 수 있어야
하며, 활성 화면 필터와 인원 상한은 별도다. 과거 FIELD 값을 오늘 방문으로 추정하지 않는다.

## 선행 계약과 적용 순서

- #311 소유 V41, `FieldVisitRegistration`, `FieldVisitDto`, `FieldVisitRegistrationRepository`가 선행한다.
  이 작업공간의 네 파일은 #311 제공본을 검증용으로 반영한 것이다. 병합할 때 독립
  마이그레이션으로 다시 만들지 말고 #311 최종본과 동기화한다. #310은 V42를 소유한다.
- [User#202](https://github.com/School-of-Company/Expo-User-Server/issues/202):
  `PUT /internal/expos/{expoId}/participants/{participantId}/field-visits/{registrationId}`,
  `{visitDate,generation,status:CANCELLED,operationId}` → 204. 같은 세대 CANCELLED는
  terminal이며 오래된 세대는 새 등록에 영향을 주지 않는다. 다른 유효 등록을 보존한다.
- [Attendance#204](https://github.com/School-of-Company/Expo-Attendance-Server/issues/204):
  같은 prefix의 `POST /cancellation-status`,
  `PUT /cancellation-guards/{operationId}`, `POST /cancellation-guards/{operationId}/commit`,
  `DELETE /cancellation-guards/{operationId}`. 모든 요청은 방문일/세대를 전달하고
  조회/acquire에는 세션으로 검증한 전화번호를 body에 전달한다. 조회는 ID/날짜/세대와
  날짜별 첫입장/출석/guard/연결 연수자를 명시적으로 반환해야 한다.
- [Report#92](https://github.com/School-of-Company/Expo-Report-Server/issues/92):
  #311 원천에서 FIELD 방문일/취소 시각·실행자와 generation별 이벤트를 소비한다.
  Application 구현만으로 관리자 엑셀 변경을 완료했다고 보지 않는다.

V41 → User/Attendance 새 계약 → V42/#310 소비 및 #311 등록/자격 연결 → Report 원천
소비와 실제 통합 검증 순서다. `application.field-visits.enabled`는 기본 false다.
실제 공급자/스캔/재등록 검증 전 활성화하지 않는다. Attendance delivery도 활성화돼야 한다.
404/501/연결 실패는 503으로 닫는다. 대상별 내부 토큰만 사용한다.

`tb_field_visit_event`의 CANCELLED는 이후 Application#308/Notification#110에서 소비할
수 있는 성공한 로컬 취소 원천이다. 키는 `(registrationId,generation,CANCELLED)`이고
operationId/occurredAt/actorParticipantId를 보존한다. 외부 취소 문자 발행·전송 완료를
뜻하지 않으며 이 작업은 SMS를 호출하지 않는다.

## 검증 범위

fake HTTP provider로 날짜·세대·ID echo와 엄격한 204, 공급자 누락/인증 오류를 검증한다.
workflow 테스트는 전날 입장, 당일 입장/퇴장, 조회 뒤 스캔 승리, acquire/abort/commit 및
User projection 실패 복구, 이전 세대 재시도, 보호 조건 재검사와 1회 이력을 확인한다.
기존 owner HTTP 테스트에 FIELD의 세션/CSRF/세대 전달 회귀를 추가했다.

`FieldCancellationPersistenceTests`는 실제 Flyway/PostgreSQL에서 취소·이력·복구결정의
롤백/재시작과 취소→재등록 세대 보존을 확인한다. Docker 데몬 미실행 환경에서는 실행할
수 없다. fake provider 테스트는 실제 Attendance 스캔 원자성이나 User/Report 통합 QA의
대체 증거가 아니다. 운영 DB, 실제 SMS, 배포는 검증하지 않았다.

2026-10-22 로컬 결과: FIELD gateway/workflow, owner HTTP/eligibility/User gateway,
취소 deadline 및 기존 Attendance adapter/status의 8개 클래스에서 51건 통과(실패/스킵 0).
`ktlintCheck`, `spotlessCheck`, `git diff --check` 통과. 모든 Gradle 실행은 공통
`/tmp/expo-application-gradle.lock` 부모 Perl flock과 지정된 단일 worker/heap 제한을 사용했다.
PostgreSQL 테스트는 실행을 시도했으나 `Could not find a valid Docker environment`로
컨테이너 초기화 단계에서 실패했다. 누적 entered를 차단 조건에 넣는 임시 변이에서는
전날 입장 회귀가 실패함을 확인하고 해당 변이를 제거했다.

PR 준비 시 전체 `build ktlintCheck spotlessCheck`도 같은 자원 제한으로 실행했다.
조립·린트는 통과했고, 테스트 188건 중 149건 통과 및 Docker 초기화 단계 39건 실패로
전체 build는 실패했다. 실제 PostgreSQL 테스트 본문과 서비스 간 통합은 여전히 미검증이다.
