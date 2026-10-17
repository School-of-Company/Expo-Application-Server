# 레거시 연수자 귀속 연결·해제 (#136)

사용자 결정에 따라 연결·해제는 기존 Expo `APPLICATION_CANCEL` 권한을 사용한다.
원본 Bearer JWT를 매 요청 검증하고 결과의 adminId만 User actorAdminId로 전달한다.
User는 현재 Admin 행의 ROLE_ADMIN을 다시 검증한다. 클라이언트 관리자 ID는 받지 않는다.
명시적인 양수 traineeId·participantId와 공백 아닌 1~500자 reason이 필수다.
이름·전화번호·대표자 번호로 찾거나 자동 연결하지 않는다.

## API와 복구

기본 경로: `/application/admin/expos/{expoId}/legacy-trainee-attributions`.

| 요청 | 의미 |
| --- | --- |
| POST 기본 경로 | 명시한 두 ID 연결·기존 연수 신청 귀속 채택 |
| POST `/revoke` | User 연결 해제 후 해당 귀속만 정리 |
| GET `/operations/{operationId}` | APPLICATION_READ 검증 후 상태·결과 조회; reason·JWT 미반환 |

POST에는 원본 Authorization과 UUID Idempotency-Key가 필요하다.
본문은 `{"traineeId":7,"participantId":9,"reason":"운영자가 확인한 자료와 확인 방법"}`이다.
같은 작업 ID의 요청·관리자·행위가 바뀌면 409이고, 완료된 작업은 저장한 결과를 반환한다.
PENDING은 같은 요청·작업 ID로 재전송해 복구한다.

V24는 귀속·프로그램 정의 projection과 영속 작업 원장을 추가한다.
원래 trainee·신청 ID와 좌석을 보존하고 신규 신청·접수번호·폼 답변·출석·승급 이벤트를 만들지 않는다.
채택한 프로그램 정의는 제출 당시 폼 스냅샷으로 사용하지 않는다.
시간 검사마다 귀속된 실제 연수 신청의 Expo 현재 정의를 batch 조회한다(추가 원격 호출 비용이 있다).
Application에서 준비 중인 일정 변경 정의가 있으면 그것을 우선한다.
정의 누락·조회 실패·잘못된 시간은 신청 시 오류로 반환하고 승급은 보류한다. 기존 신청 취소는 계속 처리한다.

User 호출 전 PENDING을 커밋하고 HTTP 호출 동안 DB 트랜잭션·참가자/trainee/일정 잠금을 유지하지 않는다.
별도 세션 잠금은 같은 trainee의 동시 원격 작업만 직렬화한다.
User 성공 후 projection과 APPLIED를 같은 로컬 트랜잭션으로 커밋한다.
응답 유실·로컬 실패는 PENDING을 유지하고, 재시도에서 User changed=false여도 로컬 반영을 마친다.
최초 시도의 확정 4xx는 REJECTED다. 불확실한 이전 시도가 있으면 뒤의 오류로 gate를 풀지 않는다.
User 호출 전 검증 실패는 원격 시도 이력이 없을 때만 FAILED로 종료한다.
FAILED는 같은 작업 ID로 원래 오류를 반환하며, 원인 해결 후 새 작업 ID로 요청한다.
이전 User 호출 이력이 있으면 사전 검증 실패도 PENDING을 유지한다.

PENDING 참가자의 신규 신청·승급과 행사 일정 변경을 보류한다.
승급 보류 때문에 기존 신청 취소가 실패하지 않는다.
채택된 실제 연수 신청은 STANDARD↔TRAINING 시간 검사와 일정 변경 참가자 조회에 포함한다.
레거시 추가·replace도 같은 귀속 검사를 사용한다.
원래 신청의 취소·삭제는 실제 신청 행과의 join에서 즉시 제외된다.
해제는 귀속 projection만 지우며 연수 신청·좌석·출석·일반 신청 이력을 삭제하지 않는다.
단, 해당 행사·참가자·연수자의 CONFIRMED 또는 WAITING 원장 신청이 있으면 User 호출 전에 409로 거절한다.
소유자·관리자·학생/보호자 취소가 현재 User 연결을 필요로 하므로 먼저 신청을 취소한 뒤 해제한다.
이미 존재한 시간 충돌을 자동 취소하거나 일정을 옮기지 않는다.

### 기존 사전 검증 실패 PENDING의 운영 정리

V25 배포 후에도 기존 PENDING을 자동 종료하지 않는다. 운영자는 Application 인스턴스와 작업 진입을
중지하고 진행 중인 요청이 없음을 확인한 뒤 다음 절차를 수행한다. attempts가 0일 때만 User 호출 전임이
확실하다. attempts가 1 이상이면 응답 유실 가능성이 있으므로 이 절차를 사용하지 않고 같은 작업 ID로 복구한다.
psql에서 대상 UUID를 `\set operation_id '확인한-작업-UUID'`로 지정한 뒤 실행한다.

```sql
BEGIN;
SELECT id, state, attempts, error_status
FROM tb_legacy_trainee_attribution_operation
WHERE id = :'operation_id'::uuid FOR UPDATE;
UPDATE tb_legacy_trainee_attribution_operation
SET state = 'FAILED', error_status = COALESCE(error_status, 503), completed_at = clock_timestamp()
WHERE id = :'operation_id'::uuid AND state = 'PENDING' AND attempts = 0;
COMMIT;
```

UPDATE가 정확히 1행인지 확인하고 작업 ID·근거를 운영 기록에 남긴다. 재시작 후 원인을 해결하고 새 작업 ID로
요청한다. User 호출 여부를 증명할 수 없는 작업을 강제로 포기하거나 연결·좌석을 직접 지우지 않는다.

## User 계약과 통합

[User PR #148](https://github.com/School-of-Company/Expo-User-Server/pull/148)의
POST /internal/trainees/participant-link와 /revoke를 사용한다.
검토 head: `50b71b187b28844a084a33cc5522cb9d44389801`.
2026-10-17(KST) PR #148 병합 확인: `916b2557e8374344b4adf8c23e6ae09802855b53`.

User 400/403/404/409는 보존하고 내부 인증 실패·잘못된 성공 응답은 502,
설정 누락·네트워크/5xx 장애는 503이다. 응답 ID·타입·changed를 검증한다.
대상별 Expo/User 내부 토큰만 사용하고 관리자 Bearer는 User에 보내지 않는다.

APPLICATION_LEGACY_TRAINEE_ATTRIBUTION_ENABLED는 기본 false다.
User #148의 배포 반영과 실제 Expo APPLICATION_CANCEL/User 토큰 연동 확인 후 활성화한다.
User 계약에는 요청 ID/세대 fencing이 없으므로 타임아웃 뒤 지연 요청을 배제할 수 없다.
미해결 PENDING 상태에서 다른 연결·해제를 시작하지 않으며 자동 반대 작업·ID 보정은 하지 않는다.

#125의 PR #138 V23 → #136 V24 순서로 병합·배포한다. 귀속 채택을 새 PRE/FIELD 좌석으로 분류하지 않는다.
그 뒤 #147 V25를 배포한다. FAILED 작업이 있으면 이를 종료로 취급하지 못하는 구버전 코드로 롤백하지 않는다.
#145 시각 파싱 수정은 별도 브랜치에서 관리하며, 실제 Expo 시각 형식 연동 확인 전 귀속 기능을 활성화하지 않는다.
#138 원장 변경과 #128의 PR #139 PrepareProgramChange 검사·테스트 stub 변경은 통합 시 양쪽을 보존한다.
귀속 union/검사와 legacy apply·replace 호출 외 공급자 어댑터·좌석 로직은 덮어쓰지 않는다.

## 검증

사용자 지시에 따라 #122 검사 종료 전 #136 Gradle을 실행하지 않는다.
좁은 검사부터 순차 실행하며 결과를 아래에 기록한다.

```bash
./gradlew test --tests 'team.startup.application.LegacyTraineeAttributionGatewayTests' --no-daemon --max-workers=1
./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests.legacy attribution*' --no-daemon --max-workers=1
```

이후 기존 시간 충돌·승급·취소·일정 변경 및 내부 신청 회귀와 formatter/CI 검사를 순차 실행한다.
2026-10-17 검증 결과(모두 --no-daemon --max-workers=1로 순차 실행):

- LegacyTraineeAttributionGatewayTests 11건 통과.
- ProgramSessionApplicationTests의 legacy attribution 16건 및 기존 시간·승급·취소·일정 회귀 5건 통과.
- ProgramApplicationServiceTests 8건, TrainingProgramApplicationHttpTests 29건,
  StandardProgramApplicationHttpTests 9건 통과. 최종 단일 실행 합계 78건, 실패 0건.
- ktlintFormat 후 ktlintCheck·spotlessCheck 통과, git diff --check 통과.
- PR 준비 시 전체 `build ktlintCheck spotlessCheck --no-daemon --max-workers=1`을 실행하였으며,
  기준 브랜치의 `AdminOperationsHttpTests` 3건 실패를 확인하였다. 해당 회차 시간 fixture 보완은 별도 PR #137에 있다.
  전체 검사의 최종 결과는 PR 본문에 기록한다. 배포된 실제 Expo/User 연동은 실행하지 않았다.
