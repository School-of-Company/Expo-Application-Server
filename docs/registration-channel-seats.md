# 회차 접수 경로별 좌석 원장: #125

## 현재 계약과 구현 (2026-10-17)

Application 기준은 `origin/develop` `9b25487dda55cd143ee78a757dac68c0b3286aba`이다. #126 시간 충돌과 #128 학생·보호자 묶음 신청이 이미 포함되어 있다. 아래의 2026-10-16 기록은 #134 당시의 판단이며 현재 기능 상태가 아니다.

[Expo PR #128](https://github.com/School-of-Company/Expo-Expo-Server/pull/128)은 `aba115d4e7ef55c7130d2e77565522d1e8ea03ac`으로 머지되었다. 따라서 과거 기록의 “Expo OPEN이므로 필드 없음”이라는 보류 근거는 폐기한다. 공급자는 `preRegistrationCapacity`/`onsiteCapacity`를 필수 정수로 제공하며 각각 0 이상, `capacity`는 두 값의 합이자 1 이상이다. `allowedOccupations`는 자격이고 역할별 `channels`는 새 공급자 계약에서 제거되었다. 공급자도 현장 개방·권한·대기 정책을 정하지 않았다.

### 접수 경로와 원장

- 기존 참가자 세션·CSRF 기반 `/application/expos/{expoId}/program-sessions/{programType}/{programId}/applications`와 `/application-batches`는 **사전 접수 전용**이다. 서버가 분리 정의를 확인하고 `PRE_REGISTRATION`을 저장한다. 요청 본문·쿼리·헤더의 임의 `ONSITE` 값은 좌석 선택에 사용하지 않는다. 사전등록 소유 사실로 과거 신청 경로를 추정하지 않는다.
- V23은 `tb_program_session_application.admission_route`와 인덱스 및 경로 변경 방지 트리거를 추가한다. 기존 행은 NULL(미상)이며 기존 직업별 `channel`과 별개다. `ONSITE`는 원장에서 표현할 수 있으나 현장 신청을 만드는 API는 아직 없다.
- 신청은 기존 참가자→묶음→프로그램 잠금에서 경로 정원과 전체 정원을 함께 확인한다. 학생·보호자는 재사용 보호자를 제외한 신규 인원 전체가 사전 좌석에 들어갈 때만 원자적으로 확정한다. 프로그램 시간 충돌·직업 자격·묶음 중복·취소 보호를 유지한다.
- 취소는 상태와 legacy 신청을 같은 트랜잭션에서 변경하며 `admission_route`를 보존한다. 따라서 해당 경로의 확정 수만 줄어든다. 저장·이벤트 실패는 기존 트랜잭션으로 신청/좌석/멱등 영수증을 함께 롤백한다. 멱등 replay는 새 정원을 다시 소비하지 않고 원래 결과를 반환한다. 불확실한 User 연수자 연결 실패도 로컬 좌석이나 영수증을 임의 확정하지 않는다.
- 분리 정책은 두 필드가 함께 있어야 하고 숫자 문자열·소수·null·음수·Int 범위 초과·합계 불일치와 `channels` 동시 제공을 거절한다. 두 필드가 모두 없는 과거 정의는 역사적 전체/직업별 정책으로 읽을 수 있다. 이미 관찰한 분리 정의의 미승인 변경이나 전체 정원 정책으로의 복귀는 허용하지 않는다. 새 공급자 응답과 배포 환경의 실제 기존 정책 데이터는 운영 전 대조해야 한다.

### 대기와 승급의 안전 경계

분리 정의의 **신규 WAITING과 자동 승급은 비활성**이다. 사전 좌석이 없으면 현장 좌석이나 공통 `waitingCapacity`로 우회하지 않고 409이며 User 연수자 연결 전 거절한다. 기존 미분리 정의의 대기·FIFO·묶음 승급은 유지한다. 대기 이력이 활성인 미분리 회차를 분리 정책으로 바꾸는 prepare는 거절한다. 비정상적으로 분리 정의에 대기 행이 남아 있어도 신규 신청이 추월하거나 자동 승급하지 않는다. 경로별 대기 상한, 원래 경로 유지, 현장 개방 때 사전 잔여석 이전은 별도 결정이 필요하다.

### 조회와 정원 변경

참가자 후보 GET의 `admissionSeats`와 관리자/내부 `program-session-applications/current-sessions` 각 item의 `admissionSeats`는 동일 원장 계산을 사용한다. 관리자 접수 목록·상세에는 `admissionRoute`를 추가한다. 기존 목록 순서·식별자·상태·인증과 `Cache-Control: no-store`를 유지한다.

```json
{
  "preRegistration": {"capacity": 20, "confirmedCount": 12, "remaining": 7},
  "onsite": {"capacity": 10, "confirmedCount": 0, "remaining": 9},
  "unknownConfirmedCount": 1,
  "waitingEnabled": false,
  "onsiteAdmissionEnabled": false
}
```

전체 확정 수의 원천은 기존 standard/training 신청 테이블이고 새 경로별 확정 수는 원장이다. 두 수를 더하지 않는다. 미상 수는 `max(0, 전체 확정 - 사전 확정 - 현장 확정)`이며 경로별 잔여석은 `max(0, 경로 정원 - 해당 경로 확정 - 미상)`이다. 위 예에서 전체 확정은 13명이며 미상 1명을 두 번 등록한 것이 아니다. 미상의 실제 경로가 밝혀지기 전 양쪽의 가용량을 보수적으로 줄이는 방식이다. 두 잔여석을 더해 전체 가용량으로 해석하지 않는다. 전체 `capacity.remaining`은 기존 물리적 전체 빈자리이며 즉시 사전 접수 가능 수가 아니다. `waitingCapacity`도 설정값이며 신규 대기 활성 여부는 `waitingEnabled`로 구분한다.

prepare는 같은 잠금에서 경로별 확정+미상보다 정원을 줄이는 것을 거절한다. 전체 합계가 같아도 점유된 사전 좌석을 현장으로 옮길 수 없다. 기존 직업 채널 레이아웃 보호·삭제·시간 변경 검증도 유지한다. 새 정의의 apply/reconcile은 기존 승인된 pending 정의와 revision 비교를 그대로 따른다.

좌석 조회는 묶음 잠금 뒤 기존 프로그램 ID의 공유 advisory lock을 정렬해서 획득한다. 따라서 프로그램 잠금만 사용하는 legacy 일괄 삭제/교체와도 확정 총수·경로별 수를 읽는 도중에 경쟁하지 않는다.

### 현장 참가자 직접 신청: 증명 계약 필요

사용자 결정은 **참가자 직접 현장 신청**이며 관리자 대리 접수가 아니다. 클라이언트가 `ONSITE`를 보내거나 행사 날짜·현장 URL·사전등록/QR 소유만으로 좌석을 선택할 수 없다.

Attendance develop `537b1af471d5a02b6495d546de364772b7b95679`의 계약을 확인했다.

- [취소 상태 서비스](https://github.com/School-of-Company/Expo-Attendance-Server/blob/537b1af471d5a02b6495d546de364772b7b95679/src/main/kotlin/team/startup/expo/domain/attendance/service/impl/GetCancellationStatusServiceImpl.kt)는 `entered`를 User에서 받아 전달한다. User의 `CancellationStatusResDto`는 이를 “날짜와 상관없이 입장 기록이 한 번이라도 있는지”로 정의한다. 오늘 현장 체류나 퇴장을 증명하지 않는다.
- [EntryLog](https://github.com/School-of-Company/Expo-Attendance-Server/blob/537b1af471d5a02b6495d546de364772b7b95679/src/main/kotlin/team/startup/expo/domain/attendance/entity/EntryLog.kt)에는 행사·대상 종류/ID·날짜·입장·퇴장이 있다. 그러나 [EntrySummaryController](https://github.com/School-of-Company/Expo-Attendance-Server/blob/537b1af471d5a02b6495d546de364772b7b95679/src/main/kotlin/team/startup/expo/domain/attendance/presentation/EntrySummaryController.kt)는 관리자용 집계/최근 제한 건수이며 특정 참가자의 현재 현장 여부를 보장하는 내부 계약이 아니다. QR 조회는 입장 증명이 아니다.
- Expo의 새 정원 정의는 `onsiteCapacity`가 현장 개방·권한을 뜻하지 않음을 명시한다. 회차별 현장 모집 시작/종료나 참가자 현장 증명 필드는 없다.

필요한 후속 계약은 다음과 같다. 아래는 제안이며 공급자에 존재하는 API로 가장하지 않는다.

1. Attendance 내부 조회/검증: 서버가 인증된 참가자의 `expoId`, `participantId`와 연수자인 경우 권위 있는 `traineeId` 매핑을 전달한다. 응답은 같은 행사·참가자에 귀속된 대상, KST 행사 날짜, `enteredAt`, nullable `exitedAt`, `observedAt`/증명 유효시간을 제공해야 한다. 기록 없음·다른 행사·타인·퇴장·오래된 기록·취소 처리 중·원천 장애는 현장 승인으로 취급하지 않는다. 동반자·학생·보호자는 신청 대상자 각각의 증명이 필요한지 결정해야 한다.
2. 제품 결정: 오늘 미퇴장 입장 기록을 현장 자격으로 인정할지, 현장 QR로 발급한 짧은 유효시간의 참가자/행사 귀속 proof가 필요한지 확정한다. 입장 기록은 기기의 현재 물리적 위치를 증명하지 않으며, 퇴장과 신청의 경쟁까지 원자적으로 막아야 한다면 consume/hold 계약도 필요하다.
3. Expo 개방: 사전 모집 기간과 다른 현장 개방 시간이 필요하면 해당 회차의 권위 있는 개방 상태·기간·revision을 제공한다. `onsiteCapacity > 0`을 개방 신호로 쓰지 않는다.
4. 위 계약 승인 뒤 참가자 세션·CSRF·소유권을 유지하는 현장 직접 신청 진입점을 추가하고, 서버 검증 성공 시에만 `ONSITE`를 불변 기록한다. 대기·승급·경로 간 잔여석 이전 정책은 별도로 확정한다.

### 병행 작업과 배포

#128 작업자에게 DTO·원장·서비스·prepare·조회·테스트 변경 범위를 사전 전달했다. `RegistrationGateway` 및 별도 공급자 어댑터는 이 작업에서 수정하지 않는다. 통합 주의사항에 따라 #128의 `ProgramGuardianPolicy` 타입과 nullable `guardianPolicy` 필드를 동일하게 보존했다. `AdmissionCapacityDeserializer`는 정수 필드에만 적용하며 상위 정책을 재구성하지 않는다. 정책 ON인데 검증 provider가 없으면 단일 신청은 503이고, prepare는 잘못된 구조나 활성 신청의 정책 변경을 거절한다.

두 정원과 ON guardianPolicy가 Expo 응답→DTO→저장 스냅샷→재읽기에서 함께 보존되는 회귀와 provider 누락503 회귀를 추가했다. #128 어댑터·인터페이스 변경은 아직 통합하지 않았으므로, 최종 병합에서는 `validateSingle(command, definition, occupation)` 호출과 묶음 query의 `request.participants`, 구조화 폼 검증을 함께 보존하고 어댑터를 포함한 실제 HTTP 회귀를 수행해야 한다. V23 번호 중복 여부와 새 DTO 필드는 통합 직전 재확인한다.

배포 순서는 V23 → Application 경로 좌석 지원 → 실제 Expo 정의/변경 승인 계약 검증이다. Expo V37만 먼저 운영 적용하지 않는다. 기존 정책은 자동 분리하지 않으며, NULL 경로·활성 대기·직업 채널 이력이 있는 회차의 운영 전환 계획을 확인한다. 현장 증명/개방·대기 계약 전에는 이슈 전체 완료·현장 운영 개방으로 보고하지 않는다.

검증 결과는 아래 최신 검증 기록에 별도로 남긴다. 과거 #134의 482건 통과를 이번 구현의 결과로 사용하지 않는다.

## 최신 검증 기록

- 최신 핵심 회귀: `./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests.split seats*' --no-daemon --max-workers=1` 성공, **12/12 통과**, 실패/오류/스킵 0, Gradle 1분/XML 테스트 21.017초. 다른 워크스페이스 Gradle 종료 뒤 순차 실행했으며 wrapper/Test Executor 종료를 확인했다.
- 표준·연수 취소/멱등/불변 경로, 마지막 사전 좌석 경쟁, ONSITE 위조, 잔여석, 저장 실패 롤백·재시도, 묶음 원자성, 공급자 비정상 정원, 경로별 축소/변경, 기존 미상 행과 대기 회차 전환 보호를 검증했다. 추가한 guardianPolicy 보존/공급자 누락503 2건과 ONSITE 원장 취소 1건도 이번 12건에 포함한다. ONSITE 테스트는 신뢰된 원장 행을 직접 준비한 취소 검증이며 현장 신청 API 성공을 뜻하지 않는다.
- 최초 신규 회귀의 2건 실패는 트리거의 일반 SQLSTATE(P0001)와 기대한 제약 위반 분류가 달랐기 때문이다. 트리거에 23514를 명시한 뒤 위 9/9 재검증이 통과했다.
- `spotlessApply` 성공. 추가한 관리자 2건을 포함하는 3클래스 회귀와 `ktlintCheck spotlessCheck` 명령은 testClasses까지 컴파일된 뒤, 사용자의 #128 검증 차례를 보장하기 위해 중단했다(exit 130). 해당 회귀·lint의 성공으로 집계하지 않는다. 최초 Mac 부하로 사용자가 중단한 실행도 실패로 집계하지 않는다.
- `git diff --check` 통과. 사용자 속도 우선 지시에 따라 관리자 2건/전체 회귀·최종 lint·전체 CI/build 및 실제 서비스 실연동은 추가 실행하지 않았다. guardianPolicy 타입은 #128 소스와 동일함을 `cmp`로 확인했지만 어댑터를 포함한 #128 병합 후 전체 통합 검증은 별도다.
- 현장 직접 접수 증명은 사용자 답변·공급자 계약 미결 상태로 안전 차단한다. 경로별 신규 대기/승급도 미개방이다. 워크스페이스 댓글에 최신 결과를 기록하며 커밋·푸시·PR 생성·이슈 종료는 하지 않았다.

이후 통합 시 사용할 검증 명령(이번 속도 우선 검증에서는 실행하지 않음):

```sh
./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests' --tests 'team.startup.application.ProgramSessionPublicHttpTests' --tests 'team.startup.application.AdminApplicationHttpTests' ktlintCheck spotlessCheck --no-daemon --max-workers=1
```
