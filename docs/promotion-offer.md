# 대기 참여 제안 (#144)

2026-10-17 승인 정책을 구현한다. 대상은 사전등록 회차 대기 일행과 일반·연수 프로그램의 사전(PRE) 대기다. 현장 접수와 이미 확정된 신청은 대상이 아니다.

## 정책

- 회차 시작 날짜(KST)의 전날 00:00부터는 빈자리가 나도 자동 확정하지 않는다. FIFO 선두에게 좌석을 임시 확보한 참여 제안을 만들고, 대표자가 수락해야 확정한다. 그 전에는 기존 자동 승급을 그대로 쓴다.
  - 프로그램 시작 시각은 Expo의 KST 로컬 `startedAt`, 사전등록 회차는 `startedAt`(Instant)를 KST 날짜로 바꿔 쓴다. 프로그램 시작 시각을 읽을 수 없으면 제안 방식으로 처리한다(수락 없이 확정하지 않음).
- 문자는 09:00(포함)~18:00(미포함) KST에만 보낸다. 밤에 생긴 빈자리는 다음 09:00에 보내는데, 그 시각이 기존 마감 이후면 제안을 만들지 않는다.
- 응답 기한 = 공급자 실제 접수 시각 + 6시간, 단 기존 마감을 넘지 않는다. 야간을 빼지 않으며 마감을 연장하지 않는다. 기존 마감은 프로그램이 `min(applicationEndedAt, promotionEndedAt)`, 사전등록이 `min(PRE 폼 endDate, 회차 startedAt)`이다. 나중에 Expo에서 더 이른 마감을 관찰하면 진행 중인 제안도 그 마감으로 줄인다.
- 응답 가능한 구간이 없으면(발송 가능 시각 또는 공급자 접수 시각이 마감 이후) 보내지도 확정하지도 않는다.
- 거절과 응답 만료는 해당 대기를 종료(프로그램 `CANCELLED`, 사전등록 `REJECTED`+`withdrawn_at`)하고 다음 FIFO 대상에게 제안한다. 처리자는 #143 규칙(거절 USER+대표자, 만료 SYSTEM)으로 기록한다.
- 발송 확정 실패(`DELIVERY_FAILED`)는 거절로 보지 않는다. 응답 기한까지는 본인이 내 신청 화면에서 수락·거절할 수 있고, 기한이 지나면 무응답과 같이 `EXPIRED`로 대기를 종료하고 다음 FIFO 대상에게 제안한다(#226). 공급자 접수 전에 실패해 기한이 없으면 실패 보고 시각 + 6시간(기존 마감을 넘지 않음)을 기한으로 둔다. 실패 보고가 마감 이후면 기한 없이 마감에 `VOIDED`로 확보만 푼다.
- 결과 불명(`UNKNOWN`)은 거절·무응답으로 보지 않는다. 좌석을 유지하다가 마감에 `VOIDED`로 확보만 풀고 대기는 남긴다.

## 저장과 상태

V27 `tb_promotion_offer`가 좌석 확보다. V37은 공급자 접수 없이 실패 보고로 생긴 응답 기한을 허용한다. 대기 행은 `WAITING`을 유지하므로 기존 CHECK·트리거·#143 이력 계약은 바뀌지 않는다.

| 상태 | 뜻 | 좌석 |
| --- | --- | --- |
| SCHEDULED | outbox. 아직 Kafka ACK 없음 | 확보 |
| PUBLISHED | Kafka ACK. 공급자 결과 미수신 | 확보 |
| SENT | 공급자 접수. 응답 기한 진행 | 확보 |
| DELIVERY_FAILED | 확정 실패. 응답 기한 진행(접수 전 실패면 보고 + 6시간) | 확보(기한까지) |
| UNKNOWN | 결과 불명 | 확보(마감까지) |
| ACCEPTED / DECLINED / EXPIRED / VOIDED | 종료 | 해제 |

- 확보 좌석은 대상이 아직 `WAITING`일 때만 센다. 그래서 대상이 다른 경로로 취소되면 확보가 바로 풀리고, 스케줄러가 제안을 `VOIDED`(`TARGET_CLOSED`)로 정리한다.
- 확보 좌석은 사전 잔여석, 전체 물리 정원(현장 접수 포함), 채널 정원, 프로그램 정의 변경(정원 축소 거절), 사전등록 `occupied`·잔여석·관리자 요약에 모두 포함한다. 분리 정원(#125)에서는 사전 좌석에서만 확보하고 현장 좌석으로 승급하지 않는다.
- 학생·보호자 묶음은 묶음 전체의 신규 인원만큼 확보한다. 이미 확정된 보호자를 재사용하면 링크일 뿐이라 좌석을 다시 잡지 않는다.

## 계약

### Application → Kafka `application.promotion-offer.requested` v1

`{"version":1,"eventId":UUID,"offerId":UUID,"expoId":UUID}`. 승급 완료 이벤트(`application.program-session.promoted`)와 별개다. 발송 시간대에만, DB 트랜잭션 없이 세션 advisory lock으로 발행하며, ACK 뒤 기록 전에 중단되면 같은 eventId로 다시 보낸다(Notification이 중복 제거).

### Notification → Application (내부 토큰)

- `POST /internal/promotion-offers/check`: 이벤트 그대로 보낸다. `eligible=false`이면 `reason`이 `NOT_CURRENT_OFFER`(현재 제안 아님·대기 종료·마감) 또는 `OUTSIDE_SEND_WINDOW`이다. eligible이면 대표 번호, 수신자 이름, 프로그램/회차명·KST 일시·장소, 좌석 수, `responseUrl`, `hardDeadline`, `sendWindowEndsAt`, `responseHours`(6)을 준다.
  - Notification은 파싱하지 못한 응답을 끝없이 다시 조회하므로(#236) 이름·제목·장소를 Notification 스키마(앞뒤 공백 제거, UTF-16 길이 이름·제목 100·장소 255, `\p{C}\p{Zl}\p{Zp}` 금지)에 맞춰 준다. 줄바꿈·제어 문자는 공백으로, 보이지 않는 서식 문자는 지우고 그 밖의 글자는 바꾸거나 자르지 않는다. 장소가 비거나 길이를 넘으면 장소를 빼고 보낸다. 이름이나 제목이 그렇게 되면 지어내지 않고 `FAILED`(`INVALID_MESSAGE_TEXT`)로 기록한 뒤 `NOT_CURRENT_OFFER`를 준다. 그래서 #226 실패와 같이 기한까지 내 신청 화면에서 응답할 수 있고 좌석도 그때까지 유지된다.
- `POST /internal/promotion-offers/{offerId}/deliveries`: `{eventId, status, attemptId, attemptedAt, providerAcceptedAt, reason}`.
  - `ACCEPTED`은 공급자 성공 응답을 받은 실제 시각(`providerAcceptedAt`)이 필요하다. `attemptedAt`과 다른 값이어야 하고 미래(1분 초과)나 시도 이전이면 400이다. 같은 결과의 재전송은 멱등이고 다른 결과는 409다.
  - `FAILED`→`DELIVERY_FAILED`(기한이 없으면 보고 시각 + 6시간, 마감 이전일 때만), `UNKNOWN`→`UNKNOWN`, `DELIVERED`는 기록만 한다. `SKIPPED`+`OUTSIDE_SEND_WINDOW`이면 새 eventId로 다음 09:00에 다시 예약하거나, 시간이 없으면 `VOIDED`(`NO_SEND_INTERVAL`)로 둔다.
  - eventId가 현재 값과 다르면 409다.

### Client → Application (참가자 세션 쿠키 + CSRF)

- `GET /application/expos/{expoId}/my-applications/promotion-offers`: 대표자의 제안 목록. 프로그램 제안은 관리 일행(묶음은 저장된 대표자)으로, 사전등록 제안은 세션 검증 번호로 찾는다. `status`는 `PENDING|OPEN|ACCEPTED|DECLINED|EXPIRED|ENDED`, `respondable`, `expiresAt`을 준다.
- `POST …/promotion-offers/{offerId}/acceptance`, `POST …/{offerId}/decline`: 본인 제안이 아니면 404다. 수락은 잠금 아래 자격·묶음·시간 충돌·사전등록 확정·마감·정원을 다시 확인한다. 실패하면 409 `{"code": 사유}`이며 대기를 취소하지 않는다(기한 안 재시도 가능). 공급자 장애·잠금 경합은 503이다. 수락·거절 반복은 같은 결과를 돌려준다.
- 사전등록 수락은 예약을 `RESERVED`로 바꾼다. 이후 기존 복구 흐름이 User에 저장하면서 QR이 발급된다. 문자 발송만으로 User·Attendance에 반영되는 일은 없다.
- 프로그램 수락 확정에도 V15 분류가 적용된다. 승급 완료 알림 기능이 켜져 있으면 기존 "신청이 확정되었습니다" 문자가 별도로 나간다. 이는 실제 확정 안내이며 제안 문자와 다른 이벤트다.

## 설정과 배포

- `APPLICATION_PROMOTION_OFFERS_ENABLED`(기본 false), `APPLICATION_PROMOTION_OFFERS_BOOTSTRAP_SERVERS`, `APPLICATION_PROMOTION_OFFERS_RESPONSE_URL`(https, `{expoId}` 치환, 예: Client `/application/forms/{expoId}`). 켜져 있으면 응답 URL이 없거나 https가 아닐 때 시작에 실패한다.
- 꺼져 있으면 #144 이전처럼 자동 승급한다. 이미 만들어진 제안의 좌석 확보·수락·거절은 계속 동작하지만, 발행·만료·정리 스케줄러는 멈춘다. 켠 뒤에는 활성 제안이 남아 있는 동안 끄지 않는다.
- 배포 순서:
  1. 선행 작업 #125 → #143 병합
  2. V27을 포함한 Application을 기본값(false)으로 배포
  3. Notification 제안 모듈 배포(`PROMOTION_OFFER_*`, Application 내부 토큰 별도)
  4. Client 제안 화면 배포
  5. Gateway/Config에서 `/application/expos/{expoId}/my-applications/promotion-offers/**` 경로·쿠키·CSRF 전달 확인
  6. 테스트 서버에서 가짜·실제 공급자 대사 검증
  7. 활성화
- 롤백: 활성 제안(`state IN SCHEDULED, PUBLISHED, SENT, UNKNOWN, DELIVERY_FAILED`)이 0건일 때만 #144 이전 코드로 되돌린다. 이전 코드는 확보 좌석을 세지 않는다.

## 남은 한계

- Solapi 접수 응답에는 접수 시각 필드가 없다. 그래서 Notification이 성공 응답을 받은 시각을 `acceptedAt`으로 쓰며, 이 값은 요청 직전의 `attemptedAt`과 다르다. 결과 불명은 수동 대사(`/internal/promotion-offer-notifications/{eventId}/reconciliation`)로 전달 결과만 확인하고, 접수 시각을 추정해 응답을 열지 않는다.
- 수락 재검증은 기존 자동 승급과 같이 프로그램 잠금 아래에서 User·Expo 조회를 한다. 발행·발송 확인·결과 기록에는 열린 트랜잭션이 없다.
- 실제 Kafka·Solapi·Gateway 연동과 운영 데이터 검증은 하지 않았다.
