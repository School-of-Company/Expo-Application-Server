# 사전등록 대기 도입 범위 설계 (Application #99)

2026-10-13 사용자 결정으로 이번 행사에 사전등록 대기를 도입합니다(P0). 근거는 앞서 확인한
예시 관리자 설정(사전등록 회차별 일반·대기 정원)이며, 숫자는 예시일 뿐 운영자가 회차마다
입력합니다. 이 문서는 구현 설계이며 현재 코드와 V1~V16 마이그레이션은 바꾸지 않습니다.
구현은 별도 이슈에서 진행하고, #34의 2026-10-09 "대기 없음" 결정과 AGENTS.md 사전등록
계약은 구현 PR에서 이 결정으로 갱신해야 합니다. 기준: Application develop `78efc13`, User develop `2af1a6f`,
2026-10-13. 1차 검토 의견(승급 경계, 대기 취소 후 승급, User 계약, 멱등 응답,
대기 정원 표시, 플래그·롤백)을 반영했습니다.

### 예시 사이트 확인 (2026-10-13, `https://req.glink.kr/festa26/apply/calendar.php`)

- 사전등록 회차마다 정원·확정·대기(대기 정원)·잔여와 "사전등록 가능" 상태를 공개 표시합니다.
  당시 값은 회차별 정원 1,000 / 대기 정원 100이었으나 예시 운영값이므로 코드나 기본값에 쓰지 않습니다.
- 마감 행은 "신청 기간 종료 또는 관리자 마감"으로 표시해 신청 기간 종료와 수동 마감을 구분합니다
  (P6·P6a와 같은 구분).
- 일행 수용 단위, 승급 순서·종료, 대기 취소, 대기·승급 안내 문자는 페이지에 없습니다.
  P2~P13은 예시가 아니라 이 문서의 결정으로 정합니다.

## 1. 정책 차이

| 항목 | 이전 요구 (#34 본문·10-07/08 댓글·10-10 D05 보완) | 현행 (#34 10-09 결정·10-12 완료 조건, V7, AGENTS.md) |
| --- | --- | --- |
| 수용 | 확정석+대기석 합으로 일행 전원을 받을 수 있으면 수락. 한 일행 안에서 일부 확정/일부 대기 허용 | 남은 확정석 ≥ 일행 인원이면 전원 확정, 아니면 전원 거절 |
| 대기 정원 | 회차별 `waitingCapacity` | 없음. Expo는 `waiting_capacity`를 계속 내려주지만 `PreregisterSessionDefinition`이 `@JsonIgnoreProperties("waitingCapacity")`로 버림 |
| 승급 | 자동승급 기본 ON, 회차별 ON/OFF·OFF 시 수동, FIFO, 회차 시작 이후 중단 | 없음. 취소하면 좌석만 반환. V7이 `auto_promote` 삭제, WAITING 상태 금지 |
| User 저장 | 저장 후 판정 (거절·대기자에게도 QR 문자) | 판정·좌석 선점 후에만 고정 requestId로 User 저장 (D 방식) |
| 집계 | 확정+대기 | 확정 참가자만 `tb_preregister_count_outbox`로 Expo PUT |
| QR·입장 | 확정자만 QR, 대기자는 안내, 일행 링크에서 승급 시 QR 추가 | User에 저장된 사람 = 확정자. Attendance 회차 PUT은 확정자만 |
| 관리자 요약 | 대기 인원 표시 | `waitingParticipants` 필드는 있으나 항상 0 |

프로그램/연수 대기(#44, V9·V10·V15, `tb_program_session_*`, `ProgramWaitlist`)는
별도 원장입니다. 이 설계는 그 테이블·FIFO 인덱스·승급 publisher를 재사용하지도
변경하지도 않습니다. 다만 승급 경계 규칙은 #44와 같게 맞춥니다(3절 P6).

## 2. 대기를 넣으려면 해결해야 하는 제약 (현재 코드 기준)

1. **User 저장 = QR 문자.** User는 대표자와 동행자를 한 requestId·한 트랜잭션으로
   저장하고 저장 즉시 이벤트를 내며, Notification이 QR 문자를 보냅니다.
2. **Attendance는 회차 기록이 없는 참가자를 검사하지 않습니다.** User에 저장된
   대기자는 회차 PUT이 없으므로 User 코드로 입장할 수 있습니다.
3. **일행을 나눠 저장하는 계약이 없습니다.** 기존 일행 응답은 새로 문자 받은 동행자만
   돌려줄 수 있어 이미 불일치 보류(`needsReview`) 대상입니다.
4. **회차 정의 비교.** 저장된 정의(TEXT)에는 `waitingCapacity`가 없습니다. 필드만
   추가하면 `PreregisterSessionLedger.current()`의 `stored != remote`가 409를 냅니다.
   이력이 있는 회차는 정의 변경도 막힙니다.
5. **응답.** 사전등록 POST는 본문 없는 201입니다. Client는 201을 등록 완료로 봅니다.
6. **구버전 코드는 새 상태를 모릅니다.** 현재 `PreregisterRegistrationWorkflow.recover()`는
   COMPLETED/REJECTED가 아닌 예약을 모두 User에 저장하고 CONFIRMED로 넣습니다. 같은
   `Idempotency-Key` 재시도(`resume`)가 WAITING 행에 닿으면 정원 검사 없이 확정과
   QR 문자가 나갑니다. 따라서 대기 기능 배포 뒤 `78efc13` 이하로 되돌리면 안전하지 않습니다.

1~3 때문에 "일행 혼합 수용"은 User·Notification·Attendance 계약을 모두 바꿔야 하고,
"일행 전체 대기"는 User 저장을 승급 시점으로 미루면 Attendance·Notification 변경 없이
기존 좌석 선점·복구 흐름을 재사용할 수 있습니다.

## 3. 결정 사항 (2026-10-13 사용자 확정)

아래 표는 모두 확정입니다. P1·P12는 다른 결정(P6·P13)에 따라 함께 정해졌습니다.

| # | 항목 | 결정 | 근거 |
| --- | --- | --- | --- |
| P0 | 이번 행사에 사전등록 대기를 도입하는가 | **도입** | 사용자 결정. 예시 관리자 설정의 회차별 대기 정원 확인을 근거로 함. 예시 숫자는 고정값으로 쓰지 않음 |
| P1 | 회차별 대기 정원 출처 | Expo 기존 `waitingCapacity`. 0이면 대기 미사용. 값은 운영자가 회차마다 입력하고 코드 기본값 없음 | Expo #70에서 이미 저장·검증 |
| P2 | 일행 수용 단위 | **전체 단위.** 확정석 부족 + 대기석 ≥ 일행 인원이면 일행 전체 대기, 그 외 전원 거절 | 2절 1~3. 혼합은 User 부분 저장·대기 QR 차단·Attendance 상태 추가 필요 |
| P3 | 대기열이 있을 때 확정석이 비면 | 대기열이 비어 있지 않으면 신규 신청도 대기 | 새 신청이 대기자를 앞지르지 않음 |
| P4 | 승급 방식 | 자동만 | V7에서 ON/OFF 삭제, #44도 수동 제외 |
| P5 | FIFO 선두 일행이 빈 자리보다 클 때 | **엄격 FIFO.** 선두가 들어갈 때까지 뒤 일행도 대기. 빈자리 정체는 관리자 요약에 표시 | 순서 설명이 단순하고 공정. 대가: 자리가 비어 있을 수 있음 |
| P6 | 승급 경계 | **#44와 동일.** 승급 시점에 ① 사전 신청 기간 종료 전 ② 수동 `closed` 아님 ③ 회차 `startedAt` 전을 모두 만족할 때만 승급 | 합의된 "신청 마감 이후 취소분 미승급" 유지. 프로그램과 다르게 할 근거 없음 |
| P6a | 수동 `closed` 중 승급 | 차단, 재개(`closed=false`) 관찰 시 승급 재확인 | #44 보수적 차단과 동일 |
| P7 | 신청 기간 출처 | 현재 PRE 접수 검사와 같은 PRE Form `startDate`/`endDate` (박람회 단위, 회차별 아님) | `RegistrationWorkflow`가 이미 이 값으로 사전 등록 기간을 판정. 회차별 기간은 Expo 변경 필요 |
| P8 | 입장·QR | 확정만. 대기자는 User 미저장이라 QR·입장 대상 아님 | 2절 1~2가 자동으로 충족 |
| P9 | 집계 | Expo 집계는 확정만, 대기는 Application 관리자 요약 | 대기자는 User 참가자 ID가 없어 참가자별 PUT 불가 |
| P10 | 대기 접수 안내 | 응답 화면과 대기 조회 화면만(문자 없음). 승급은 User 저장 이벤트의 기존 QR 문자가 안내 | 문자는 Notification 계약이 새로 필요 |
| P11 | 대기 중 취소·조회 주체 | 대표자(인증 세션)·관리자. 일행 전체 단위로만 취소 | 대기 일행은 User 참가자가 없어 참가자별 선택 불가 |
| P12 | 경계 이후 남은 대기 | 별도 상태 없음. 회차가 시작되면 대표자 조회는 `PROMOTION_ENDED`, 새 대기 접수 중단, 그 대기는 대표 번호의 다른 회차 신청을 막지 않음 | 추가 상태·작업 불필요 |
| P13 | 대기 정원 변경·기존 회차 적용 | 채택된 값은 이력 후에도 현재 대기 인원 이상으로 변경 가능(#268). 이력 있는 기존 회차는 운영자가 명시 채택하기 전까지 대기 미적용(4절 "대기 정원 적용") | `PreparePreregisterSessionChange`의 하한 검사 |

혼합 수용과 대기 접수 문자는 이번 행사 범위에서 제외했습니다.

## 4. 상세 설계

### 상태 모델

대기는 참가자가 아니라 **예약(일행) 단위**로 `tb_preregister_reservation`에 둡니다.
`tb_preregister_application`(CONFIRMED/CANCELLED)과 V7 제약은 그대로입니다.

```
신청 ─┬─ 확정석 충분 && 대기열 없음 ─→ RESERVED → (User 저장) → COMPLETED   (기존 경로)
      ├─ 대기석 충분 ─────────────────→ WAITING ─┬─ 승급 → RESERVED → COMPLETED
      │                                         ├─ 대표자·관리자 취소 → REJECTED + withdrawn_at
      │                                         └─ 경계 경과 → (조회 시 승급 종료)
      └─ 그 외 ───────────────────────→ 409 전원 거절 (User 호출 0)
```

- 대기 취소는 새 상태를 만들지 않고 기존 종료 상태 REJECTED(`error_status=409`)에
  `withdrawn_at`을 기록합니다. 구버전 코드도 REJECTED를 종료로 취급하므로 취소된 대기가
  User 저장으로 되살아나지 않습니다. 신규 비종료 상태는 WAITING 하나뿐입니다.
- 좌석 계산(같은 Expo 배타 잠금 아래): `confirmedUsed = CONFIRMED 참가자 + RESERVED applicants`,
  `waitingUsed = WAITING applicants 합`.
- 같은 대표 번호의 중복 신청은 `samePhoneInUse`에 WAITING을 포함해 막습니다. 동행자 중복은
  기존처럼 승급 후 User 결과 단계에서 `needsReview`로 보류합니다.

### 승급

- **계기:** ① 확정 취소(`CancelPreregisterSessionServiceImpl`, 소유자 취소의 PREREGISTER
  로컬 트랜잭션, 관리자 취소) ② 예약 REJECTED(User 거절) ③ **대기 취소**(선두가 빠지면
  다음 선두가 들어갈 수 있음) ④ `closed=false` 재개 관찰 ⑤ 주기 재확인 스케줄러
  (원격 관찰 실패·지연 시 안전망). 계기는 단일 전용 스레드에 같은 회차를 한 번만 넣고,
  요청 응답과 공용 스케줄러 스레드는 Expo·Form 호출을 기다리지 않습니다. 스케줄러는 열려 있고
  시작 전이며 저장 정원 기준으로 선두가 들어가는 회차만 고릅니다.
- **경계(P6):** 승급 직전 Form 기간을 트랜잭션 밖에서 관찰하고(`ledger.observe`와 같은 방식),
  잠금 안에서 `now`로 판정합니다. Form·Expo 관찰 실패 시 승급하지 않고 스케줄러가 다시 봅니다.
  마감 시각 이후에는 그 전에 생긴 빈자리라도 승급하지 않습니다(#44 `ProgramWaitlist.promote`와 동일).
- **처리:** 잠금 안에서 회차 대기 선두만 봅니다. `capacity - confirmedUsed ≥ applicants`이면
  조건부 UPDATE로 WAITING→RESERVED, `attempts=0`, `next_attempt_at=now`. 남는 자리가 있으면
  다음 선두로 반복하고, 들어가지 않으면 멈춥니다(엄격 FIFO). 이후 기존
  `PreregisterRecoveryScheduler`가 저장된 User requestId·스냅샷으로 User 저장과
  Attendance/집계 outbox를 처리합니다. 승급 전용 전송 코드는 만들지 않습니다.
- **정체 표시:** 관리자 요약 회차 항목에 `waitingHeadApplicants`, `freeConfirmedSeats`,
  `promotionBlockedReason`(`HEAD_TOO_LARGE`·`CLOSED`·`SESSION_STARTED`)을 추가해 선두가 커서
  자리가 비어 있는 상태를 운영자가 볼 수 있게 합니다. 신청 기간 종료는 Form 기준이라 DB 요약에는
  넣지 않았습니다.

### 같은 멱등키 재시도 응답

| 예약 상태 | 응답 | 비고 |
| --- | --- | --- |
| COMPLETED | 201 무본문 | 기존과 동일 |
| WAITING | 202 `{"status":"WAITING","sessionId":…,"position":…}` | 대기를 새로 만들지 않음 |
| RESERVED, 첫 신청 | 기존과 동일(복구 진행, 성공 201, 실패 시 좌석 유지 오류) | 현행 계약 변경 없음 |
| RESERVED, 승급분 | 복구 진행. 성공 201, 일시 실패·운영자 보류 시 202 `{"status":"PROCESSING"}` | 이미 받아들여진 대기자에게 거절처럼 보이는 오류를 주지 않음 |
| REJECTED + `withdrawn_at` | 409 `{"code":"WAITLIST_WITHDRAWN"}` | 같은 키로 대기 재생성 금지. 재신청은 새 키 |
| REJECTED (User 거절) | 기존과 동일 | |

Client는 2xx를 모두 "등록 완료"로 처리하지 않고 201 확정 / 202 WAITING / 202 PROCESSING을
구분합니다. PROCESSING은 같은 키로 재조회합니다. 키 없이 보낸 요청은 지금처럼 매번 새
신청이므로 Client(#345)는 제출마다 안정적인 키를 보내야 합니다.

### 대기 정원 적용

Application이 실제로 적용하는 값을 정의 스냅샷에 명시하고, 0으로 조용히 해석하지 않습니다.

- 저장 정의에 `waitingCapacity` 키가 없고 신청 이력이 없으면: 잠금 아래 Expo 값을 채택해 저장
  (현재 첫 관찰 시 정의 저장과 동일).
- 키가 없고 이력이 있으면: 상태 `UNADOPTED`. 이 회차는 대기 접수를 하지 않고(현행 전원 거절
  유지) 정의 비교에서 이 키만 제외해 확정 접수는 계속합니다. 관리자 요약과 잔여석 응답에
  `appliedWaitingCapacity: null`, `expoWaitingCapacity`, `waitingCapacityStatus: "UNADOPTED"`를
  내려 불일치를 드러냅니다.
- 운영자는 내부 API `PUT /internal/expos/{expoId}/preregister-sessions/{sessionId}/waiting-capacity`
  (`{"expected": Expo 현재 값}`)로 명시적으로 채택하거나, 미사용으로 둡니다. 기대값이 Expo
  관찰값과 다르면 409.
- Expo 관리자 화면은 Application 적용값과 상태를 함께 표시합니다(Expo 표시 작업, 정의 변경 없음).

### 데이터 마이그레이션 (새 버전 파일만)

구현 시점 최신 develop의 다음 번호(현재 원격 브랜치 기준 V17 미사용)를 사용합니다.

- `tb_preregister_reservation.state` CHECK에 `WAITING` 추가, `withdrawn_at TIMESTAMPTZ` 추가.
- `queue_seq BIGINT`(전용 시퀀스, 대기 진입 시 부여)와
  `(expo_id, session_id, queue_seq) WHERE state = 'WAITING'` 인덱스. FIFO는 서버 접수 순서.
- `ix_preregister_reservation_retry`·`due()`는 RESERVED만 보므로 WAITING은 User 재시도 대상이 아님.
- 기존 행 이관 없음. V5·V5_1·V7 수정 없음. purge의 기존 삭제 조건
  (`state <> 'RESERVED' OR attempts = 0`)은 WAITING·취소된 대기를 지우므로 유지합니다.

### API/DTO (서비스별, 모두 가산)

| 서비스 | 변경 |
| --- | --- |
| Application 등록 `POST /application/pre-standard/{expoId}` | 위 재시도 응답 표 |
| Application 잔여석 `GET …/preregister-sessions/{sessionId}/capacity` | `remaining`(대기열이 있으면 0)·`maxApplicants` 의미 유지, 예시 사이트 표시용 `confirmed`·`waiting`·`appliedWaitingCapacity`와 `waitingRemaining`·`maxWaitingApplicants`·`waitingCapacityStatus` 추가. 공개 조회가 Form을 부르지 않도록 마감 사유는 넣지 않고, Client가 이미 받는 폼 기간과 `closed`로 표시 |
| Application 대표자 대기 조회·취소 | `GET /application/expos/{expoId}/my-applications/preregister-waitlist`, `POST …/my-applications/preregister-waitlist/cancellation`. `PARTICIPANT_SESSION` → `participantSessionPhone`으로 검증 번호를 얻어 예약 조회(V8 번호 인덱스). 취소는 CSRF 보호 |
| Application 관리자 | 요약 회차 항목에 `waitingParticipants`·`waitingReservations`·`waitingHeadApplicants`·`freeConfirmedSeats`·`appliedWaitingCapacity`. 관리자 대기 취소는 기존 사전등록 관리자 취소와 같은 내부 API `POST /internal/expos/{expoId}/preregister-reservations/{requestId}/withdrawal`(일행 단위, 이미 취소면 204) |
| Application 내부 | 예약 조회 응답에 WAITING·`withdrawn`, 대기 정원 채택 API |
| Expo | 정의 변경 없음. 관리자 화면에 Application 적용값·불일치 표시 |
| User | **기존 세션 계약 확인 후 결정.** 코드 근거로는 변경 불필요: User `2af1a6f`의 `ParticipantSessionTests`는 참가자 저장 없이 OTP 인증만으로 발급한 세션에 `/internal/participant-sessions/resolve`가 검증 번호를 반환하고, Application은 이미 User 저장 전 등록 단계(`RegistrationWorkflow`)에서 이 조회를 씁니다. 배포 인스턴스 확인과 세션 만료 후 재인증 흐름을 확인한 뒤 확정 |
| Attendance·Notification | 변경 없음(P10 문자 없음) |
| Client (#345, #340) | 201/202 WAITING/202 PROCESSING/409 WITHDRAWN 분기, 대기 조회·취소 화면, 잔여석 대기 필드 |

### 동시성·멱등성

- 신청·취소·승급·대기 정원 채택·정의 변경 준비·purge는 기존
  `pg_advisory_xact_lock(34, hashtext(expoId))` 배타 잠금으로 직렬화하고, 잔여석 조회는
  공유 잠금 없이 읽기 전용 REPEATABLE READ 스냅샷 하나를 사용합니다. 원격 관찰(Expo 정의, Form 기간)은 잠금 밖에서 합니다.
- 배타 잠금 트랜잭션은 `lock_timeout=3s`를 사용하며 사전등록 원장은 SQLSTATE `55P03`만
  409로 변환합니다. 예약·User 요청 스냅샷은 유지하고 같은 멱등 키로 재시도합니다.
- WAITING→RESERVED, WAITING→REJECTED(취소)는 상태 조건부 UPDATE라 재실행해도 중복 승급·
  중복 취소가 없습니다.
- 대기 취소와 승급 경합: 잠금 안에서 WAITING일 때만 취소. 이미 RESERVED면 409
  "승급 처리 중"을 반환하고 COMPLETED 후 기존 취소 경로를 안내합니다.
- User 호출은 트랜잭션 밖. 승급 후 응답 유실·불일치는 기존 RESERVED 복구 규칙(좌석 유지,
  같은 requestId 재시도, 불일치 보류)을 따릅니다.
- 소유자 취소(`tb_owner_cancellation_delivery`) PREREGISTER 로컬 트랜잭션에 승급 계기를
  넣되 원격 Attendance 펜스 순서는 바꾸지 않습니다.

### 플래그·배포·롤백

- 플래그 이름은 의미대로 `APPLICATION_PREREGISTER_WAITLIST_INTAKE_ENABLED`(기본 false).
  **신규 대기 접수만** 제어합니다. OFF여도 기존 WAITING의 승급·조회·취소는 계속 동작합니다.
  "대기 기능 전체 중지" 스위치는 두지 않으며, 남은 대기를 끝내려면 운영자가 일행 단위로
  명시 취소합니다(V7처럼 조용히 승급·삭제하지 않음).
- 2절 6 때문에 2단계로 켭니다. 구현은 한 변경이며, 플래그 OFF 배포가 R1 역할을 합니다.
  1. **R1:** V17과 새 코드를 플래그 OFF로 배포. 대기열이 없으면 기존 동작과 같고, 새 코드는
     WAITING·취소된 대기를 User에 저장하지 않습니다.
  2. **R2:** 플래그 ON으로 신규 대기 접수 시작.
  R2 이후 롤백은 플래그 OFF(R1)까지만 허용합니다. `78efc13` 이하 코드로 되돌리려면 WAITING 행이
  0건임을 확인해야 하며, 0건이 아니면 앞으로 고쳐서 배포합니다.
- 전체 순서: 결정 기록(#34·#99 댓글, AGENTS.md 갱신) → Expo 회차별 `waitingCapacity` 점검·정정 → R1(플래그 OFF 배포) →
  User 세션 조회 배포 확인 → Client 배포(#345) → 테스트 서버 다중 서비스 검증 → 플래그 ON.

### 검증

- 플래그 OFF 회귀: 기존 `PreregisterRegistrationHttpTests`, `PreregisterSessionPersistenceTests`,
  `PreregisterSessionCapacityHttpTests`, `PreregisterMigrationTests` 전부 통과.
- 접수: 확정석 부족·대기석 충분/부족 경계, 대기열 존재 시 신규 신청 대기, 대기자 User 호출 0.
- 승급: 동시 취소 두 건, 엄격 FIFO 선두 초과 정체 표시, **선두 대기 취소 후 다음 일행 승급**,
  신청 기간 종료·`closed`·`startedAt` 직전·정확한 시각·이후, 재개 후 승급, Form 관찰 실패 시
  미승급 후 스케줄러 재확인, 승급 후 User 응답 유실·불일치 보류.
- 멱등: 재시도 응답 표 전 행, **대기 취소 뒤 같은 키 재도착 시 대기 미생성**, 새 키 재신청.
- 정의: 키 없는 저장 정의(이력 없음 채택 / 이력 있음 `UNADOPTED`·표시·명시 채택·기대값 409),
  이력 있는 회차의 대기 정원을 대기 인원보다 작게 줄이면 409(#268).
- 롤백: 새 코드가 WAITING·취소된 대기 예약을 User에 저장하지 않음.
- 구현 테스트(2026-10-13): `PreregisterWaitlistHttpTests`(접수·멱등·엄격 FIFO·대기 취소 후 승급·신청 기간·마감/재개·
  회차 시작·대표자 조회/취소·명시 채택·동시 취소), `PreregisterMigrationTests`(V17 제약),
  `OwnerApplicationHttpTests`(세션·CSRF), `PreregisterRegistrationHttpTests`(플래그 OFF 잔여석 계약).
- 프로그램 대기(#44) 테스트 무변경 통과.
- CI: `./gradlew build ktlintCheck spotlessCheck --no-daemon`.

## 5. 구현 이슈 분할안 (등록은 사용자 승인 후)

2026-10-13: Application 범위 A~D는 `feat/99-preregistration-waitlist-design` 작업트리에 구현했습니다(커밋 전).

| 순서 | 저장소 | 범위 | 선행 |
| --- | --- | --- | --- |
| A | Application | R1: 마이그레이션, 새 상태를 아는 복구·응답, 정의 `waitingCapacity` 적용 규칙·채택 API | 결정 기록 |
| B | Application | R2: 전체 단위 대기 접수, 재시도 응답 표, 잔여석 필드, 신규 접수 플래그 | A 배포 |
| C | Application | 승급 계기 5종, 엄격 FIFO, 경계(P6), 정체 표시, 스케줄러 | B |
| D | Application | 대표자 대기 조회·취소, 관리자 대기 취소·요약 | B, User 세션 조회 배포 확인 |
| E | Expo | 운영 데이터 `waitingCapacity` 점검, 관리자 화면 적용값·불일치 표시 | A |
| F | Client | 응답 분기, 대기 조회·취소 화면 (#345에 연결 가능) | B, D |
| G | User | (확인 결과 필요할 때만) 참가자 없는 세션의 검증 번호 조회 보완 | D 전 확인 |

## 6. 다른 서비스·Client 연계 이슈 (2026-10-13 조회)

| 저장소 | 이슈 | 상태 | 이 결정의 영향 |
| --- | --- | --- | --- |
| Expo-Client | #345 참가자 신청 화면 | OPEN | 201 확정/202 WAITING·PROCESSING/409 WITHDRAWN 분기, 대기 조회·취소 화면, 회차 화면의 확정·대기·대기 정원·잔여·마감 사유 표시. 본문의 "확정+대기 용량"·"마감/대기" 항목과 직접 연결 |
| Expo-Client | #340 동행자 추가 잔여석 제한 | CLOSED | 확정 잔여석만으로 동행자 추가를 막으면 대기 접수가 불가. 닫힌 이슈는 재오픈하지 않고 #340을 참조하는 새 Client 이슈로 등록(사용자 승인 후) |
| Expo-Client | #347 신청 관리자 화면 | OPEN | 회차 현황에 대기 인원, 승급 정체 사유, 대기 정원 적용 상태(`UNADOPTED`·채택), 대기 일행 취소 |
| Expo-Notification-Server | #28 카카오 템플릿 | OPEN | 템플릿 후보에 "대기 신청·대기→확정"이 있음. P10(문자 없음)이면 사전등록용으로는 불필요하고, 승급 시 기존 QR 문자를 씀. 프로그램 대기용 템플릿과 구분해 기록 필요 |
| Expo-Application-Server | #100 현장 운영 도구(QR 분실·기존 신청 조회) | OPEN | 신청 조회에 대기 일행(User 참가자 없음)을 포함할지 정해야 함. 대기자는 QR이 없으므로 재발송 대상 아님 |
| Expo-Gateway / Expo-Config-Server | 해당 이슈 없음 | - | 새 경로를 기존 `/application/expos/{expoId}/my-applications` 아래(`/preregister-waitlist`, `/preregister-waitlist/cancellation`)에 두었습니다. Gateway·Config가 이 하위 경로와 쿠키·CSRF를 그대로 전달하는지 배포 전에 확인하고, 아니면 새 이슈 필요 |
| Expo-Expo-Server | 열린 이슈 없음 | - | 관리자 화면에 Application 적용 대기 정원·불일치 표시, 운영 데이터 `waitingCapacity` 점검(5절 E). 새 이슈 필요 |
| Expo-User-Server | 열린 이슈 없음 | - | 참가자 없는 세션 조회는 develop 테스트로 확인됨. 배포 인스턴스 확인 결과에 따라서만 이슈 필요(5절 G) |
| Expo-Attendance-Server | #120 퇴장·재입장 규칙 | OPEN | 영향 없음. 대기자는 User·Attendance에 기록되지 않음 |

새 이슈 등록과 기존 이슈 댓글은 사용자 승인 후 진행합니다.
