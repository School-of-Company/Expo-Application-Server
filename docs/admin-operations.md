# 관리자 신청 운영 API (#93)

모든 공개 API는 매 요청 원본 Bearer JWT를 Expo에 전달하여 현재 권한을 확인한다. 내부 토큰과 `adminId`는 클라이언트가 권한 증거로 제출하지 않는다. 응답에는 `Cache-Control: no-store`를 적용한다.

## 프로그램 신청 취소

`POST /application/admin/expos/{expoId}/program-session-applications/{applicationId}/cancellation`

본문 없이 호출한다. 성공은 200 `{applicationId, status: "CANCELLED"}`이다. 자동 승급 여부와 최신 이력은 원장 및 `/activities`를 다시 조회한다. 사전등록 취소나 정책을 무시하는 강제 취소는 포함하지 않는다.

1. Expo `GET /internal/expo/{expoId}/admin-application-cancel-access`에 원본 JWT를 전달한다. 응답의 행사 ID, 양수 관리자 ID, `APPLICATION_CANCEL`, `EXPO_ADMIN_APPLICATION_CANCEL_V1`, `EXPO_ADMIN`을 모두 확인한다. `APPLICATION_READ` 성공 응답으로 취소를 승인하지 않는다.
2. 신청의 행사 소속을 검증한다. 없는 신청과 다른 행사 신청은 404이다. 이미 취소한 신청도 현재 쓰기 권한을 재검증한 뒤 복구를 수행하고 200을 반환한다.
3. 기존 참가자 취소와 같은 신청별 PostgreSQL 세션 잠금 및 durable delivery 큐를 사용한다. User `POST /internal/standard-participants/admin-cancellation-status {expoId, participantId}`로 입장·연수 연결을 확인한다. 전화번호·참가자 세션을 만들지 않는다.
4. Expo의 현재 취소 마감을 확인한 뒤 Attendance `PUT /internal/expos/{expoId}/participants/{participantId}/admin-cancellation-guard/{operationId}`를 본문 없이 호출한다. Attendance가 입장·프로그램 출석과 진행 중 스캔을 보호한다. acquire 후 User 상태를 다시 확인한다.
5. 참가자 → 연수자 → 프로그램 잠금 순서를 보존하고 트랜잭션 안에서 신청 및 취소 마감을 다시 확인한다. 기존 `CancelProgramSessionService`가 취소·이력·승급을 원자적으로 수행한다. 신청 또는 승급 마감 이후에는 승급하지 않는다.
6. 프로그램 취소 뒤 기존 `DELETE .../cancellation-guard/{operationId}`로 abort한다. commit으로 참가자의 전체 입장을 영구 차단하지 않는다. acquire 실패·불명확 응답·로컬 rollback에도 동일 operationId로 abort한다. abort 장애는 큐에 남아 기존 복구 스케줄러 또는 재요청이 해제한다. 취소가 이미 커밋된 뒤 abort 실패 시 503이어도 로컬 취소는 유지된다.

JWT 누락/거부는 401, 쓰기 권한 거부는 403, 대상 없음은 404, 마감·입장·출석·경합은 409이다. 잘못된 공급자 성공 응답과 내부 인증 거부는 502, 공급자 장애/미설정/Attendance 비활성화는 503이다. 공급자 실패를 권한 승인으로 바꾸지 않는다.

`V16__admin_cancellation_actor.sql`은 신청 상태 이력에 nullable `actor_id`만 추가한다. 신규 관리자 취소는 검증된 관리자 ID를 기록한다. 과거 신청·취소 및 자동 승급 처리자를 추측하거나 backfill하지 않는다.

## 사전등록 집계

`GET /application/admin/expos/{expoId}/preregistrations/summary`

기존 `APPLICATION_READ` 권한을 확인하고 Expo 공유 잠금 아래 원장을 조회한다. 대표자·동행자는 각 참가자 1명이다. `confirmedUniqueParticipants`는 행사 전체 현재 확정 고유 참가자, `cancelledUniqueParticipants`는 취소 이력 고유 참가자이며 두 값은 겹칠 수 있다. 현행 사전등록 대기 정책은 없어 `waitingParticipants=0`이다. 입장 인원이나 프로그램 신청 건수로 대체하지 않는다.

`pendingReservations`는 `RESERVED` 요청 건수, `pendingApplicants`는 해당 요청이 점유한 좌석 수다. 복구가 필요한 모호한 예약도 포함하고 확정 인원과 합쳐 고유 인원으로 표시하지 않는다. `sessions`는 회차 ID 순으로 확정·취소 고유 참가자와 예약 건수·좌석 수를 구분한다. 알려진 과거 회차도 보존하며 Expo의 아직 신청 없는 신규 회차 전체 목록을 의미하지 않는다.

## 원천별 행사 이력

한 화면은 다음 세 API를 독립 페이지로 연결한다. 다른 원천의 전역 정렬·단일 커서를 새로 만들거나 이력을 Application에 중복 저장하지 않는다.

| 원천 | 공개 Application 경로 | 필터와 페이지 |
| --- | --- | --- |
| Application | `/application/admin/expos/{expoId}/program-session-applications/activities` | `action=APPLIED\|CANCELLED\|PROMOTED`, `applicationId`, `from`, `until`, `page`, `size` |
| Expo | `/application/admin/expos/{expoId}/recruitment-history` | `type=PROGRAM_CREATE\|PROGRAM_UPDATE\|PROGRAM_DELETE\|SESSION_CREATE\|SESSION_UPDATE\|SESSION_DELETE`, `from`, `to`, `page`, `size` |
| Notification | `/application/expos/{expoId}/admin-group-messages/history` | `type=REQUESTED\|SENT\|FAILED\|RETRIED`, `from`, `to`, `limit`, `cursor` |

세 경로는 GET이다. 기간 시작은 포함, 끝은 제외한다. page는 0부터, size/limit은 1..100이며 기본 20이다. Application은 `(createdAt DESC, id DESC)`, Expo는 `(occurredAt DESC, id DESC)`, Notification은 `(occurredAt DESC, eventId DESC)` 정렬이다. Notification 커서는 제공받은 `nextCursor`를 그대로 쓰고 필터 변경 시 초기화한다. 알 수 없는 query와 중복 query는 400이다.

Application 이력은 기존 상태 변경 원천을 조회하며 `actorId`는 기록된 관리자 취소만 값이 있다. 자동 승급은 null이다. 각 행에는 신청 ID·참가자 ID·프로그램·처리유형·상태·시각이 포함된다.

Expo는 `GET /expo/{expoId}/admin-change-history` 공개 공급자 API로 같은 원본 JWT를 전달한다. Application 조회 권한 검증과 Expo 자체 검증을 모두 거치며 행사 ID·페이지·유형·기간·정렬을 확인한다. 공급자 #96이 미배포이면 실패하며 빈 이력을 만들어 주지 않는다.

Notification은 기존 그룹 발송 Expo JWT 검증 및 내부 토큰 경계를 재사용하여 `GET /internal/admin-group-messages/expos/{expoId}/history`를 대리조회한다. 조회자 ID로 이력을 제한하지 않는다. 공급자는 행사 경로로 범위를 보장하며 응답 자체에 `expoId` 필드는 없다. 응답은 typed `items`와 `nextCursor`로 제한하고 유형·시각·정렬·커서·건수·상태를 검증한다. 본문·전화번호·fingerprint·원본 공급자 오류를 전달하지 않는다. 내부 토큰 401/403은 관리자 인증 실패와 구분하여 502이다. 과거 문자 이력 backfill은 없고 보관은 공급자의 이벤트별 기본 30일이다.

## 공급자 및 배포 전제

2026-10-12 최종 확인: Expo #95는 develop 머지(`aa4108c`), Attendance #117은 develop 머지(`875d3c6`), User #118은 develop 머지(`662f7cc`), Notification #43은 develop 머지(`b95ebf6`)이다. Expo #96도 최신 변경(`b99c791`)으로 develop에 머지되었다. 머지는 실제 서비스 배포를 뜻하지 않는다.

- 병합된 Expo #95/#96을 취소 권한 `V31` → 모집 이력 `V32` 순서로 배포한다. 최초 계약의 모집 이력 V30은 #95 선행 병합을 반영하여 최신 #96에서 V32로 변경되었다. V31 환경에서의 업그레이드가 공급자 테스트로 검증되었다. Application이 공급자 마이그레이션을 수정하지 않는다.
- User #118 → Attendance #117 → Application 순서로 공급자 API를 배포한다. Notification #43과 Expo #96도 각 이력 소비 전에 배포한다.
- Expo #95 초기 취소 허용 기록은 없다. 승인된 행사·관리자별 권한을 부여해야 하며 읽기 관리자에게 자동 부여하지 않는다.
- 기존 `APPLICATION_REGISTRATION_EXPO_URL`/`USER_URL` 및 내부 토큰, Attendance URL/토큰·활성화, 그룹 발송 Expo/Notification URL·토큰을 설정한다. 관리자 취소에는 기존 프로그램 정책 조회 플래그 `APPLICATION_PROGRAM_SESSIONS_ENABLED=true`가 필요하다. 공개 등록 POST 활성화와 별개다.
- Gateway/Config 공개 라우팅, Client #347 실제 페이지·취소 후 새로고침·커서 연결, 운영 JWT와 실제 User/Attendance 스캔 경합은 배포 전 통합 검증한다. 테스트 행사 원장 데이터를 준비한다. 이 작업은 운영 플래그 활성화·배포·실제 문자 발송을 하지 않는다.

검증: `./gradlew build ktlintCheck spotlessCheck --no-daemon` 성공(6분 30초), 44개 클래스의 444개 테스트 통과, 실패·오류·건너뜀 0개. `git diff --check` 통과. `AdminApplicationHttpTests` 25개는 집계와 행사별 신청 이력, `AdminOperationsHttpTests` 13개는 신규 공개 취소·공급자 이력·동시성·abort 복구를 실제 PostgreSQL과 HTTP 공급자 stub으로 확인한다. 종료 시 기존 Spring 스케줄러가 종료된 Testcontainers 연결을 조회하는 로그가 출력되었으며 테스트 및 빌드 실패는 없었다.
