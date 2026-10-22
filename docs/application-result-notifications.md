# 신청 결과 문자 이벤트 (#308, Notification #110)

신청 확정·대기, 신청·사전등록 취소, 사전등록 회차 추가 결과, 참여 의사 확인 만료를 Notification의 `notification.sms.requested`(v1)로 보낸다. 계약 필드와 문구는 Notification `docs/notification-110-application-result-sms.md`가 기준이다.

## 쌓는 지점 (상태 변경과 같은 트랜잭션)

`ApplicationNotifications`(`Propagation.MANDATORY`)가 `tb_application_notification`(V41)에 행을 쌓는다. 승급 안내의 `tb_program_session_event.delivered_at`과는 별개다.

| 유형 | 지점 | 비고 |
|---|---|---|
| `PROGRAM_CONFIRMED` / `PROGRAM_WAITLISTED` | `ProgramSessionAdmission`, `StudentGuardianBatchAdmission`의 신규 신청 저장 직후 | 일반·연수·현장 신청과 학생/보호자 묶음. 묶음의 보호자 재사용(`reused`)은 건너뛴다. 승급(WAITING→CONFIRMED)은 기존 승급 문자라 쌓지 않는다 |
| `PROGRAM_CANCELLED` | `CancelProgramSessionServiceImpl`의 취소 성공 | 실행자가 `USER`/`ADMIN`일 때만. `SYSTEM`·`UNKNOWN`과 학생·보호자 연동 취소(`CancelStudentGuardianApplicationServiceImpl`)는 쌓지 않는다 |
| `PREREGISTER_CANCELLED` | `PreregisterCancellation.cancel`, 대기 일행 철회(`WithdrawOwnerPreregisterWaitlistServiceImpl`, `WithdrawPreregisterReservationServiceImpl`) | `USER`/`ADMIN`일 때만. 내부(UNKNOWN) 철회는 쌓지 않는다 |
| `PREREGISTER_SESSION_ADDED` | 즉시 확정되는 추가는 `completeSession` 성공 뒤, 대기 접수는 `addSession`의 예약 저장 직후 | 예약 중간 상태(`RESERVED`)가 아니라 최종 확정/대기. 대기했다 승급된 추가는 승급 확정을 신규 확정으로 다시 보내지 않는다 |
| `OFFER_EXPIRED` | `PromotionOffers.endProgramWait`/`endPreregisterWait`가 `EXPIRED`로 끝날 때만 | `VOIDED`(`HARD_DEADLINE`·`TARGET_CLOSED`)와 거절(`DECLINED`), 전송 시간대 제한에 따른 미발송은 만료가 아니다 |

같은 트랜잭션에서 쌓인 행(`batch_key = txid_current()`)은 대표자 번호별로 한 통에 묶는다. 일부 인원만 취소하면 취소된 사람만 목록에 나오고, 사전등록은 일행에 남은 활성 신청이 있으면 `scope: PARTIAL`이다.

## 발행

`ApplicationNotificationPublisher`가 5초마다 커밋된 행을 읽어 Kafka로 보낸다. Kafka 전송은 트랜잭션 밖에서 하고 `delivered_at`은 ACK 뒤에 기록한다(문자 수신이 아님).

- 보내기 직전에 현재 상태를 다시 확인한다(`ApplicationNotificationResolver`). 확정 안내는 아직 `CONFIRMED`, 대기 안내는 아직 `WAITING`, 취소·만료 안내는 실제 `CANCELLED`/철회일 때만 나간다. 아니면 `skip_reason=STATE_CHANGED`로 끝내 지연된 확정 알림이 취소 뒤에 나가지 않는다.
- 수신 번호는 대표자 번호만 쓴다. 번호가 없거나 `01…` 형식이 아니면 `NO_REPRESENTATIVE_PHONE`으로 건너뛰고 학생 개인 번호로 대신하지 않는다.
- `eventId`는 묶음에 든 행의 `dedupe_key`에서 만든 UUID라 재시도·재발행·Kafka 재전달에도 같다. 같은 상태 변경은 `dedupe_key` 유일 키로 한 번만 쌓인다. 발행 뒤 `delivered_at` 기록이 실패해 다시 보내도 Notification이 `eventId`로 중복 발송을 막는다.
- User/Expo 조회나 Kafka 발행이 실패하면 행을 지수 백오프(최대 300초)로 다시 시도한다. 신청·취소는 이미 커밋되어 되돌리지 않는다.
- 로그에는 유형과 `batch_key`만 남기고 번호·이름·본문은 남기지 않는다.
- 같은 묶음의 일부 행만 상태가 바뀌어 건너뛰면 재시도 때 묶음 구성이 달라 `eventId`가 달라질 수 있다. 첫 시도가 Kafka에는 갔지만 DB 기록이 실패한 경우에만 드문 중복이 가능하며, 영구 exactly-once는 아니다.

## 설정

```yaml
application:
  notifications:
    enabled: false              # APPLICATION_NOTIFICATIONS_ENABLED
    bootstrap-servers: ""       # APPLICATION_NOTIFICATIONS_BOOTSTRAP_SERVERS
    publish-from: ""            # APPLICATION_NOTIFICATIONS_PUBLISH_FROM (켤 때 필수, 고정 활성화 시각)
    expo-ids: ""                # APPLICATION_NOTIFICATIONS_EXPO_IDS (쉼표 구분, 비면 전체)
    topic: notification.sms.requested
```

- `enabled=false`(기본)이면 행을 쌓지 않는다. 비활성 기간의 이력이 나중에 발송 대상이 되지 않는다.
- `publish-from`보다 이전에 쌓인 행은 발행하지 않는다. 과거 이력은 소급 발송하지 않는다.
- `expo-ids`가 있으면 그 박람회만 쌓고 발행한다(테스트 박람회 한정).

## 배포 순서

1. Notification #110(소비자)을 먼저 배포한다. 이벤트가 없으므로 아무것도 나가지 않는다.
2. 이 변경(V41)을 `enabled=false`로 배포한다.
3. 테스트 박람회 ID를 `expo-ids`에 넣고 `publish-from`을 활성화 시각으로 정해 켠다. 신청→문자→취소→문자를 실제 단말로 확인한다. 공급사 접수와 단말 수신은 구분해 기록한다.
4. `expo-ids`를 비워 전체로 넓힌다.

## 범위 밖

학생·보호자 연동 취소 전용 안내, 일정·장소 변경과 프로그램 운영 취소 일괄 안내(관리자 수동 발송), 리마인더, 카카오 승인·발송. 참여 제안 거절은 본인이 답한 것이라 취소 안내를 보내지 않는다.
