# Application-Service

## API 문서

기본값은 문서 비활성화입니다. 개발 환경에서 `SWAGGER_ENABLED=true`로 켜면
`GET /application/v3/api-docs`와 `/application/swagger-ui.html`을 사용할 수 있습니다.
`SWAGGER_INCLUDE_INTERNAL=true`를 추가한 경우에만 `/internal/**` 계약과
`X-Internal-Token` 헤더 요구 사항이 문서에 포함됩니다. 실제 토큰 값은 노출하지 않습니다.
두 환경 변수는 선택이며 모두 기본 `false`입니다. 비활성화하면 문서 핸들러와
문서 경로의 익명 허용 규칙이 등록되지 않습니다. 문서 경로는 GET만 공개합니다.
내부 문서를 포함해도 실제 `/internal/**` API의 토큰 인증은 그대로 필요합니다.
문서 예시는 가상 값입니다. 토큰, 비밀번호, 실제 전화번호·code를 예시에 넣지 않습니다.
Gateway 통합 문서는 Config의 `gateway-dev.yml`에 `docs.services`와
`publicPaths`의 `GET /application/v3/api-docs`가 적용된 뒤 사용할 수 있습니다.
운영 프로필에는 문서 공개 라우팅을 등록하지 않습니다.

`APPLICATION_PROGRAM_SESSIONS_ENABLED`는 기본 `false`입니다. `true`이면 기존
내부 apply/replace도 Expo #71의 실제 회차 정의를 조회하고 정책이 설정된 신규
신청을 폼 없는 구형 경로로 추가하지 못하게 합니다. 공급자 계약이 준비된 환경에서만
활성화하세요. 공개 프로그램 신청 API와 운영 프로그램 폼 어댑터는 아직 제공하지 않습니다.

Application #47의 V6~V8을 통합하였습니다. V6~V8 다음 V9/V9_1 순서로 적용해야 합니다.
신규 연수자의 ID는 정원 수용 판정 뒤 User 연결 호출로 처음 얻으므로 현재는
회차 잠금 뒤 연수자 try-lock을 사용합니다. 모든 경로의 trainee-before-group 잠금
순서를 통일하려면 연결을 생성하지 않는 User 조회 또는 좌석 예약 계약이 필요합니다.
공개 접수 활성화 전 Form 조회·Expo 변경 승인·공개 인증 계약과 함께 해결해야 합니다.

## 일반 등록·사전등록 완료 기준 (#18, #34)

2026-10-12 이슈의 `신청서비스 소유 완료 조건`을 기준으로 검증합니다.
Client 화면, 문자 발송 및 타 서비스 운영 배포는 각 저장소에서 추적하며,
Application 구현 완료와 공개 접수 활성화는 별도로 판정합니다.

| 완료 조건 | 검증 위치 |
| --- | --- |
| 네 등록 경로의 PRE/FIELD 기간·폼·동행자·오류·멱등 계약 | `RegistrationHttpTests`, `RegistrationIntegrationContractTests` |
| 행사별 인증 설정·검증된 전화번호 사용·복구 전 세션 검증 | `RegistrationHttpTests`, `PreregisterRegistrationHttpTests`, `SecurityConfigTests` |
| 전원 선판정·좌석 선점·고정 User requestId·전체 일행 ID·응답 유실 복구 | `PreregisterRegistrationHttpTests` |
| 마지막석 경합·정의 변경 승인/revision·취소 이력·purge 후 늦은 요청 | `PreregisterSessionPersistenceTests`, `PreregisterRegistrationHttpTests` |
| 공개 잔여석·공유 조회 잠금·제출 시 정원 재확인 | `PreregisterSessionCapacityHttpTests`, `PreregisterRegistrationHttpTests` |
| Attendance 영속 전송·취소/재신청·역순 도착 방어 | `SessionAttendanceGatewayTests`, `SessionAttendanceOutboxTests`, `SessionAttendanceDispatcherTests` |

검증 명령은 `./gradlew build ktlintCheck spotlessCheck -Pkotlin.incremental=false --no-daemon`
및 `git diff --check`입니다. 공유 작업트리에서는 Gradle을 한 번에 한 터미널에서 실행합니다.

2026-10-12 검증: develop `f0f6540`(#68/V13, #69 포함) 기준 전체 테스트 290개가
실패·오류·스킵 없이 통과했습니다. 위 build·ktlintCheck·spotlessCheck 명령과
`git diff --check`도 통과했습니다.

### 공급자 계약 및 활성화 순서

1. Expo #82를 먼저 적용합니다. `GET /internal/expo/{expoId}`의 200 응답에
   `participantPhoneVerificationRequired`(boolean)와 `participantPhoneVerificationRevision`(0 이상 Long)이
   모두 필요합니다. 기존 행사는 명시적 `false`/`0`입니다. 누락·잘못된 값·조회 장애를 `false`로
   대신하지 않습니다. Application은 요청마다 조회 당시 커밋된 설정을 사용합니다.
   해당 공급자 구현은 `feat/82-event-phone-auth-setting` 브랜치의 V29를 포함합니다.
2. User #92/PR #94와 V15를 적용합니다. 등록 응답의 `groupParticipantIds`는 대표자부터 시작하는
   전체 일행이며 좌석·원장·집계·Attendance 통지에 사용합니다. `participantIds`는 이번 문자 대상입니다.
   같은 requestId는 최초 결과를 재사용합니다. 인증 행사에는 User #77의
   `POST /internal/participant-sessions/resolve`가 필요합니다. 쿠키 이름은
   `PARTICIPANT_SESSION_COOKIE_NAME`(기본 `PARTICIPANT_SESSION`)으로 공급자와 맞춥니다.
3. Attendance #75/PR #77을 먼저 적용합니다. PUT 본문의 `{sessionId, revision}`과
   DELETE의 `?revision=...`을 모두 처리해야 합니다. Application은 저장된 outbox ID로 증가하는
   revision을 계산하고 purge 보정 DELETE에도 별도 revision을 사용합니다.
4. Application 마이그레이션을 적용합니다. V7은 남아 있는 WAITING 행이 있으면 중단합니다.
   운영자가 해당 이력을 먼저 해소해야 하며 자동으로 삭제하거나 확정하지 않습니다.
5. `APPLICATION_REGISTRATION_EXPO_URL`, `APPLICATION_REGISTRATION_FORM_URL`,
   `APPLICATION_REGISTRATION_USER_URL`과 서비스별 `APPLICATION_REGISTRATION_*_INTERNAL_TOKEN`을
   수신 서비스 토큰에 맞춥니다. 기본 토큰은 `APPLICATION_INTERNAL_TOKEN`입니다.
   `APPLICATION_ATTENDANCE_URL`, `APPLICATION_ATTENDANCE_INTERNAL_TOKEN`을 설정하고
   `APPLICATION_ATTENDANCE_ENABLED=true`로 켜야 일반 사전등록이 가능합니다.
6. Gateway/Config의 등록·잔여석 경로, 참가자 쿠키 전달과 브라우저 요청 보호를 확인한 뒤
   `APPLICATION_REGISTRATION_ENABLED=true`로 공개합니다. 기본값은 계속 `false`입니다.
   실제 QR 입장에서 User 이벤트와 Attendance 전달 사이의 시간 차도 확인합니다.

공급자 코드와 HTTP 계약 테스트의 일치는 운영 배포 증거가 아닙니다.
등록 완료 조건 검증 후 Application 변경을 병합하면 #18/#34를 종료할 수 있으며,
위 공급자 배포·토큰·라우팅 확인 전에는 공개 등록을 활성화하지 않습니다.
