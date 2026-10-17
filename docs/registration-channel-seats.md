# 회차 접수 경로별 좌석 원장: #125

## 현재 계약과 구현 (2026-10-17)

Application 초기 기준은 `origin/develop` `9b25487dda55cd143ee78a757dac68c0b3286aba`이며 #126 시간 충돌과 #128 학생·보호자 묶음 신청을 포함한다. PR #138 CI 수정 시 #137 관리자 취소 회귀 수정이 머지된 develop `1cd2151`을 추가 반영했다. #134의 2026-10-16 보류 판단은 현재 기능 상태가 아니다.

[Expo PR #128](https://github.com/School-of-Company/Expo-Expo-Server/pull/128)은 `aba115d4e7ef55c7130d2e77565522d1e8ea03ac`으로 머지되었다. 따라서 과거 기록의 “Expo OPEN이므로 필드 없음”이라는 보류 근거는 폐기한다. 공급자는 `preRegistrationCapacity`/`onsiteCapacity`를 필수 정수로 제공하며 각각 0 이상, `capacity`는 두 값의 합이자 1 이상이다. `allowedOccupations`는 자격이고 역할별 `channels`는 새 공급자 계약에서 제거되었다. 공급자도 현장 개방·권한·대기 정책을 정하지 않았다.

### 접수 경로와 원장

- 기존 참가자 세션·CSRF 기반 `/application/expos/{expoId}/program-sessions/{programType}/{programId}/applications`와 `/application-batches`는 **사전 접수 전용**이다. 서버가 분리 정의를 확인하고 `PRE_REGISTRATION`을 저장한다. 요청 본문·쿼리·헤더의 임의 `ONSITE` 값은 좌석 선택에 사용하지 않는다. 사전등록 소유 사실로 과거 신청 경로를 추정하지 않는다.
- V23은 `tb_program_session_application.admission_route`와 인덱스 및 경로 변경 방지 트리거를 추가한다. 기존 행은 NULL(미상)이며 기존 직업별 `channel`과 별개다. `ONSITE`는 아래 현장 진입점에서만 기록한다.
- 신청은 기존 참가자→묶음→프로그램 잠금에서 경로 정원과 전체 정원을 함께 확인한다. 학생·보호자는 재사용 보호자를 제외한 신규 인원 전체가 사전 좌석에 들어갈 때만 원자적으로 확정한다. 프로그램 시간 충돌·직업 자격·묶음 중복·취소 보호를 유지한다.
- 취소는 상태와 legacy 신청을 같은 트랜잭션에서 변경하며 `admission_route`를 보존한다. 따라서 해당 경로의 확정 수만 줄어든다. 저장·이벤트 실패는 기존 트랜잭션으로 신청/좌석/멱등 영수증을 함께 롤백한다. 멱등 replay는 새 정원을 다시 소비하지 않고 원래 결과를 반환한다. 불확실한 User 연수자 연결 실패도 로컬 좌석이나 영수증을 임의 확정하지 않는다.
- 분리 정책은 두 필드가 함께 있어야 하고 숫자 문자열·소수·null·음수·Int 범위 초과·합계 불일치와 `channels` 동시 제공을 거절한다. 두 필드가 모두 없는 과거 정의는 역사적 전체/직업별 정책으로 읽을 수 있다. 이미 관찰한 분리 정의의 미승인 변경이나 전체 정원 정책으로의 복귀는 허용하지 않는다. 새 공급자 응답과 배포 환경의 실제 기존 정책 데이터는 운영 전 대조해야 한다.

### 대기와 승급의 안전 경계

2026-10-17 사용자 결정에 따라 분리 정의의 **사전 대기와 FIFO 승급을 복구**했다(phase A). 사전 신청은 사전 잔여석(`preRegistrationCapacity - 사전 확정 - 미상`)이 실제 한도이고 전체 `capacity`는 물리적 상한이다. 둘 중 하나라도 부족하거나 앞선 WAITING이 있으면 `waitingCapacity`·`promotionEndedAt` 안에서 WAITING으로 접수하고, 대기도 차면 409다. 현장 좌석은 사전 신청·대기 승급에 절대 빌려 쓰지 않는다. 대기 행은 접수 때 `PRE_REGISTRATION`으로 저장하고 승급해도 경로가 바뀌지 않는다.

단건·묶음 승급(`ProgramWaitlist`, `StudentGuardianBatchPromotion`)도 분리 정의에서는 사전 잔여석과 전체 정원 중 작은 값까지만 승급한다. 학생·보호자 묶음은 인원 전체가 사전 잔여석에 들어갈 때만 원자적으로 확정하고, 현장 좌석만 비어 있으면 대기에 남는다. 미분리 정의의 기존 대기·FIFO·채널·묶음 승급은 그대로다. 대기 이력이 활성인 미분리 회차를 분리 정책으로 바꾸는 prepare는 계속 거절한다.

현장 접수는 대기·승급이 없고(초과 거절), 사전 대기열에 막히지 않으며, 경로 간 자동 전용도 없다. 현장 신청 진입점은 아래 phase B 절을 따른다. 전날 참여 확인 승급은 #144 범위다.

### 조회와 정원 변경

참가자 후보 GET의 `admissionSeats`와 관리자/내부 `program-session-applications/current-sessions` 각 item의 `admissionSeats`는 동일 원장 계산을 사용한다. 관리자 접수 목록·상세에는 `admissionRoute`를 추가한다. 기존 목록 순서·식별자·상태·인증과 `Cache-Control: no-store`를 유지한다.

```json
{
  "preRegistration": {"capacity": 20, "confirmedCount": 12, "remaining": 7},
  "onsite": {"capacity": 10, "confirmedCount": 0, "remaining": 9},
  "unknownConfirmedCount": 1,
  "waitingEnabled": true,
  "onsiteAdmissionEnabled": false
}
```

전체 확정 수의 원천은 기존 standard/training 신청 테이블이고 새 경로별 확정 수는 원장이다. 두 수를 더하지 않는다. 미상 수는 `max(0, 전체 확정 - 사전 확정 - 현장 확정)`이며 경로별 잔여석은 `max(0, 경로 정원 - 해당 경로 확정 - 미상)`이다. 위 예에서 전체 확정은 13명이며 미상 1명을 두 번 등록한 것이 아니다. 미상의 실제 경로가 밝혀지기 전 양쪽의 가용량을 보수적으로 줄이는 방식이다. 두 잔여석을 더해 전체 가용량으로 해석하지 않는다. 전체 `capacity.remaining`은 기존 물리적 전체 빈자리이며 즉시 사전 접수 가능 수가 아니다. `waitingEnabled`은 사전 좌석이 없을 때 사전 대기를 받는 회차인지(`waitingCapacity > 0`)를 뜻한다. 남은 대기석과 `promotionEndedAt`는 신청 시점에 따로 확인한다. `onsiteAdmissionEnabled`은 `onsiteCapacity > 0`이고 현장 기간이 설정되었는지를 뜻하며, 지금 열려 있는지는 신청 시 판정한다.

prepare는 같은 잠금에서 경로별 확정+미상보다 정원을 줄이는 것을 거절한다. 전체 합계가 같아도 점유된 사전 좌석을 현장으로 옮길 수 없다. 기존 직업 채널 레이아웃 보호·삭제·시간 변경 검증도 유지한다. 새 정의의 apply/reconcile은 기존 승인된 pending 정의와 revision 비교를 그대로 따른다.

좌석 조회는 묶음 잠금 뒤 기존 프로그램 ID의 공유 advisory lock을 정렬해서 획득한다. 따라서 프로그램 잠금만 사용하는 legacy 일괄 삭제/교체와도 확정 총수·경로별 수를 읽는 도중에 경쟁하지 않는다.

### 현장 참가자 직접 신청 (phase B, 2026-10-17 사용자 결정·정정)

참가자가 직접 신청하며 관리자 대리 접수가 아니다. 기존 프로그램 신청 흐름과 프로그램에 연결된 폼(`formId`/version/answers)을 그대로 쓰고 질문지를 복제하지 않는다. 기존 관리자 현장 QR(`applicationType=FIELD`)은 박람회 참가 등록용이며 이 진입점과 별개다.

- 진입점: `POST /application/expos/{expoId}/program-sessions/{programType}/{programId}/onsite-applications`(`ApplyOnsiteProgramSessionService`)와 `/onsite-application-batches`(`ApplyOnsiteStudentGuardianBatchService`). 사전 진입점과 공통 접수 로직(`ProgramSessionAdmission`, `StudentGuardianBatchAdmission`)을 공유하며 트랜잭션은 각 서비스 구현이 연다. 요청 본문은 사전 경로와 같고, 참가자 세션 쿠키·CSRF·관리 참가자 소유 확인도 같다. 서버 경로만 `ONSITE`를 정하며 본문·쿼리의 경로 값은 무시한다. 멱등 스냅샷에 경로가 포함되어 같은 키로 다른 경로를 보내면 409다.
- 개방(정정된 사용자 결정): Expo 회차 정책의 `onsiteStartedAt`~`onsiteEndedAt`(둘 다 있거나 둘 다 없음, 시작 < 종료) 안에서만 받는다. 기간이 없으면 409이며 사전 모집 기간으로 대신 열지 않는다. 사전 모집 마감(`applicationEndedAt`)은 현장 접수를 막지 않는다. 프로그램 폼의 `startDate`~`endDate`도 함께 확인하지만, Expo가 폼 기간이 사전·현장 두 기간을 모두 포함하도록 저장과 폼 축소(usage)에서 강제하므로 현장 기간을 따로 막지 않는다. 짝이 맞지 않거나 순서가 틀린 기간은 공급자 계약 위반(502)이다. `closed`와 삭제 회차는 거절한다. 별도 현장 체류 증명은 두지 않는다.
- 자격: v1은 박람회 참가자이면 프로그램을 신청할 수 있었다. v2 현장 접수는 사전등록 확정자 또는 User가 박람회 현장 등록(`applicationType=FIELD`)으로 저장한 참가자를 받는다. 판정 값은 User 내부 `POST /internal/standard-participants/managed/select`·`managed` 응답의 `applicationType`(Expo-User-Server `feat/125-managed-application-type`)이며 공개 응답에는 싣지 않는다. 이 필드가 없는 옛 User 응답이면 현장 등록으로 보지 않아(사전등록 확정자만) 닫힌 채로 동작하고, PRE/FIELD 이외 값은 502다. 따라서 User 배포가 Application 현장 자격 개방보다 먼저다. 취소된 사전등록만 있는 PRE 참가자는 403이다. 참가자 후보 GET에 `onsite=true`를 주면 같은 기준으로 `eligible`을 계산한다.
- 좌석: 분리 정의만 받는다. 미분리 정의는 409다. 현장 잔여석(`onsiteCapacity - 현장 확정 - 미상`)과 전체 정원 안에서 즉시 확정하고, 부족하면 409이며 대기 행을 만들지 않는다. 사전 대기열이 있어도 막지 않고, 사전 좌석이나 대기로 넘기지 않는다. 묶음은 신규 인원 전체가 현장 좌석에 들어갈 때만 원자적으로 확정한다.
- 공통 보호: 직업 자격·묶음 중복·legacy 묶음·시간 충돌·연수자 잠금·학생보호자 정책·멱등·취소는 사전 경로와 같은 코드와 잠금 순서를 쓴다. 취소하면 `ONSITE` 좌석만 돌아오며, 현장 좌석으로 사전 대기를 승급하지 않는다.
- `onsiteAdmissionEnabled`은 `onsiteCapacity > 0`이고 현장 기간이 설정된 회차다. 지금 열려 있는지는 신청 시 기간으로 판정한다.

### 병행 작업과 배포

#128 작업자에게 DTO·원장·서비스·prepare·조회·테스트 변경 범위를 사전 전달했다. `RegistrationGateway` 및 별도 공급자 어댑터는 이 작업에서 수정하지 않는다. 통합 주의사항에 따라 #128의 `ProgramGuardianPolicy` 타입과 nullable `guardianPolicy` 필드를 동일하게 보존했다. `AdmissionCapacityDeserializer`는 정수 필드에만 적용하며 상위 정책을 재구성하지 않는다. 정책 ON인데 검증 provider가 없으면 단일 신청은 503이고, prepare는 잘못된 구조나 활성 신청의 정책 변경을 거절한다.

두 정원과 ON guardianPolicy가 Expo 응답→DTO→저장 스냅샷→재읽기에서 함께 보존되는 회귀와 provider 누락503 회귀를 추가했다. #128 어댑터·인터페이스 변경은 아직 통합하지 않았으므로, 최종 병합에서는 `validateSingle(command, definition, occupation)` 호출과 묶음 query의 `request.participants`, 구조화 폼 검증을 함께 보존하고 어댑터를 포함한 실제 HTTP 회귀를 수행해야 한다. V23 번호 중복 여부와 새 DTO 필드는 통합 직전 재확인한다.

배포 순서는 V23 → Application 경로 좌석 지원 → 실제 Expo 정의/변경 승인 계약 검증이다. Expo V37만 먼저 운영 적용하지 않는다. 기존 정책은 자동 분리하지 않으며, NULL 경로·활성 대기·직업 채널 이력이 있는 회차의 운영 전환 계획을 확인한다. Client 현장 진입 화면(Client #576)과 Gateway 경로 노출, 실제 Expo/Form/User 연동 검증 전에는 현장 운영 개방으로 보고하지 않는다.

검증 결과는 아래 최신 검증 기록에 별도로 남긴다. 과거 #134의 482건 통과를 이번 구현의 결과로 사용하지 않는다.

## 최신 검증 기록

- phase B 1차(폼 기간 개방안, 현장 기간 계약 이전이며 현재 코드의 검증이 아님): 저부하 설정으로 `./gradlew build ktlintCheck spotlessCheck --no-daemon --max-workers=1 --no-parallel`을 실행해 exit 0(10분 32초)이었다. 52개 클래스 **609/609 통과**, 실패/오류/스킵 0. `onsiteAdmissionEnabled`이 `onsiteCapacity > 0`이 되면서 기존 기대값 3건(false)을 true로 갱신했다. Client·Gateway 연동과 실제 공급자 연동은 검증하지 않았다. `git diff --check` 통과.
- phase A(분리 정의 사전 대기·승급 복구, 2026-10-17): `./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests' --tests 'team.startup.application.AdminApplicationHttpTests.current session*' ktlintCheck spotlessCheck --no-daemon --max-workers=1 --no-parallel` (저부하 init script: test heap 768m, fork 1) exit 0, 68초. `ProgramSessionApplicationTests` **134/134**, 관리자 현재 회차 3/3 통과, 실패/오류/스킵 0. 사전 만석→WAITING, 취소→FIFO 승급, 현장 좌석만 빈 경우 미승급, 학생·보호자 묶음 원자 대기·승급, 기존 미분리 대기 회귀를 포함한다. 전체 build는 phase B 이후 1회 실행 예정이며 이번에는 실행하지 않았다. `git diff --check` 통과.
- PR #138 CI 후속 수정: 테스트 줄바꿈 위반을 수정하고, 변경 중에도 게시된 정의를 읽는 기존 계약을 복구했다. 조회는 로컬 현재/준비 정의 중 일치하는 정의를 허용하며 미승인 revision과 삭제 정의는 계속 거절한다. 신청의 pending 차단은 유지한다.
- 후속 순차 검증은 참가자 조회 11건 + 좌석 핵심 13건 + 관리자 현재 회차 조회 2건 = 26/26 통과(1분 13초), 최신 develop 반영 뒤 관리자 취소 13/13 통과(39초)다. 두 실행의 ktlintCheck·spotlessCheck 및 diff-check가 통과했다. 새 CI 결과는 GitHub PR에서 확인한다.

- 최신 핵심 회귀: `./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests.split seats*' --no-daemon --max-workers=1` 성공, **12/12 통과**, 실패/오류/스킵 0, Gradle 1분/XML 테스트 21.017초. 다른 워크스페이스 Gradle 종료 뒤 순차 실행했으며 wrapper/Test Executor 종료를 확인했다.
- 표준·연수 취소/멱등/불변 경로, 마지막 사전 좌석 경쟁, ONSITE 위조, 잔여석, 저장 실패 롤백·재시도, 묶음 원자성, 공급자 비정상 정원, 경로별 축소/변경, 기존 미상 행과 대기 회차 전환 보호를 검증했다. 추가한 guardianPolicy 보존/공급자 누락503 2건과 ONSITE 원장 취소 1건도 이번 12건에 포함한다. ONSITE 테스트는 신뢰된 원장 행을 직접 준비한 취소 검증이며 현장 신청 API 성공을 뜻하지 않는다.
- 최초 신규 회귀의 2건 실패는 트리거의 일반 SQLSTATE(P0001)와 기대한 제약 위반 분류가 달랐기 때문이다. 트리거에 23514를 명시한 뒤 위 9/9 재검증이 통과했다.
- `spotlessApply` 성공. 추가한 관리자 2건을 포함하는 3클래스 회귀와 `ktlintCheck spotlessCheck` 명령은 testClasses까지 컴파일된 뒤, 사용자의 #128 검증 차례를 보장하기 위해 중단했다(exit 130). 해당 회귀·lint의 성공으로 집계하지 않는다. 최초 Mac 부하로 사용자가 중단한 실행도 실패로 집계하지 않는다.
- `git diff --check` 통과. 사용자 속도 우선 지시에 따라 관리자 2건/전체 회귀·최종 lint·전체 CI/build 및 실제 서비스 실연동은 추가 실행하지 않았다. guardianPolicy 타입은 #128 소스와 동일함을 `cmp`로 확인했지만 어댑터를 포함한 #128 병합 후 전체 통합 검증은 별도다.
- (과거 기록) 이 시점에는 현장 직접 접수를 차단했다. 이후 phase A·B로 대체되었다. 워크스페이스 댓글에 최신 결과를 기록하며 커밋·푸시·PR 생성·이슈 종료는 하지 않았다.

이후 통합 시 사용할 검증 명령(이번 속도 우선 검증에서는 실행하지 않음):

```sh
./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests' --tests 'team.startup.application.ProgramSessionPublicHttpTests' --tests 'team.startup.application.AdminApplicationHttpTests' ktlintCheck spotlessCheck --no-daemon --max-workers=1
```
