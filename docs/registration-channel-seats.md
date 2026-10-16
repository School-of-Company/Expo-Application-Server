# 회차 접수 경로별 좌석 원장: #125 계약 확인

2026-10-16 확인. Application 기준은 develop `9bc5f8f9a2327708626dfa5cbc2c8a273feb4a86`, Expo 공급자 기준은 develop `a43d80034aa38a36cea5775cb652fccccea7d864`이다.

## 확인된 범위와 이번 변경

- [Application #125](https://github.com/School-of-Company/Expo-Application-Server/issues/125)와 [Expo #123](https://github.com/School-of-Company/Expo-Expo-Server/issues/123)은 OPEN이고 댓글이 없다. 승인된 방향은 동일 회차에 사전·현장 정원을 각각 숫자로 설정하는 것이다. 역할별 정원과는 별개다.
- [공급자 ProgramPolicyRequest](https://github.com/School-of-Company/Expo-Expo-Server/blob/a43d80034aa38a36cea5775cb652fccccea7d864/src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/request/ProgramPolicyRequest.kt)는 `capacity`, `waitingCapacity`, 직업별 `channels`만 제공한다. 사전·현장 정원 필드, 누락 시 처리, 기존 정원과의 관계는 아직 없다.
- 최소 안전 범위는 기존 원장 동작을 보존하고 `ProgramSessionChannel`의 OpenAPI 설명을 직업별 모집으로 명확히 하는 것이다. 공급자에 없는 필드나 접수 구분 enum, 마이그레이션, 현장 접수 API는 추가하지 않는다. #125의 좌석 분리 기능은 미완료이며 이 문서는 계약 합의 자체가 아니다.

## 현재 원장과 조회의 의미

`ProgramSessionApplicationController`는 참가자 세션과 CSRF를 검증하고 서버가 만든 command를 전달한다. 요청 DTO에는 접수 경로가 없다. 서비스는 User 소유권·직업과 로컬 확정 사전등록을 검증한다. **사전등록 참가자라는 사실만으로 프로그램 신청 경로가 사전 접수라고 추론할 수 없다.** 기존 행을 PRE 또는 ONSITE로 일괄 변환하지 않는다.

`tb_program_session_application.channel`은 신청 당시 직업별 채널 이름이다. 채널 없는 회차와 기존 행은 NULL이다. 기존 `capacity`는 회차 전체 확정 정원이며 채널 정원 합과 같다. 전체 확정 수는 legacy standard/training 신청 테이블에서 세므로 원장 확정 수와 더하면 중복 집계된다. `waitingCapacity`는 대기 상한이고 실제 대기 인원이나 남은 대기석이 아니다. 사전·현장별 사용량과 잔여석은 현재 산출할 수 없으며 관리자 조회에서 0으로 대신 표시하지 않는다.

현재 신청은 참가자 잠금과 묶음·프로그램 잠금 안에서 최종 정원 확인과 저장을 수행한다. 멱등 replay는 기존 접수 결과를 반환한다. 취소는 원장의 상태 전이와 legacy 신청 제거를 처리하며 승급은 기존 직업 채널별 FIFO를 따른다. 아직 존재하지 않는 접수 경로별 좌석 반환·복구를 지원한다고 해석하면 안 된다. 일반 사전등록 회차의 RESERVED/WAITING 원장은 이 프로그램·연수 회차 원장과 별개다.

## 공급자 및 제품 결정 필요

1. 사전·현장 정원 필드의 이름·타입·필수 여부, 0 허용, 합계와 `capacity`의 관계, 기존 정의·신청 이력의 호환 및 전환 절차.
2. 직업별 정원과 접수 경로별 정원의 교차 제약: 두 상한을 동시에 적용하는지, 교차 셀마다 정원을 정의하는지. 현재 직업 채널을 PRE/ONSITE로 이름만 바꾸지 않는다.
3. 현장 개방 시각·권한·실제 진입 경로와 신뢰 가능한 서버 판정 원천. 클라이언트가 보낸 구분으로 좌석을 선택하게 만들지 않는다.
4. 대기 정원·큐를 경로별로 분리할지, 승급이 원래 경로를 유지하는지, 현장 개방 때 사전 잔여석을 이전하는지. 확정 전 신규 경로의 대기·자동 승급을 만들지 않는다.
5. 취소 후 반환할 원래 경로, 멱등 command/receipt 호환, 불확실한 실패의 좌석 유지와 재시도, 기존 행의 미상 분류를 합계에 반영하는 방법.
6. 정의 변경 prepare/apply 보호, 경로별 관리자 집계·잔여석 응답과 전체/직업/경로 합계의 중복 집계 방지 계약.

## 병행 작업과 통합 순서

다른 워크스페이스의 변경은 가져오거나 수정하지 않았다. 아래는 현재 소스와 이슈 범위로 확인한 예상 충돌 지점이며, 타 작업자의 최종 diff 확인은 통합 시 필요하다.

| 작업 | 겹치는 파일/영역 |
| --- | --- |
| #126 시간 중복·사유 | `ApplyProgramSessionServiceImpl.kt`, `ProgramWaitlist.kt`, `ProgramSessionApplicationRepository.kt`, `ProgramSessionApplicationDto.kt`, `ProgramSessionApplicationTests.kt` |
| #128 학생·보호자 원자적 신청 | 위 파일과 `CancelProgramSessionServiceImpl.kt`, `ProgramWaitlistRepository.kt`, 회차 컨트롤러, DB migration |
| #127 장소 계약 | `ProgramSessionApplicationDto.kt`, `PrepareProgramChangeServiceImpl.kt`, `RegistrationGateway.kt`, `ProgramSessionApplicationTests.kt` |
| #125 후속 좌석 분리 | 위 신청·대기·취소 원장과 `PrepareProgramChangeServiceImpl.kt`, 관리자 조회 및 정원 응답 |

Expo #123 정의 계약 합의 → 공급자/소비자 호환 테스트 → #126 참가자 잠금·시간 검증 → #125 경로별 원장 → #128 묶음 좌석 처리 순서를 제안한다. 확정된 merge 순서는 아니다. #128은 #126 및 Expo #125 동반 정책에도 의존한다. migration 번호는 통합 당시 최신 번호를 확인하고, 기존 V20 직업 채널 migration을 수정하지 않는다.

#127의 장소 계약 변경은 별도 미커밋 작업이며 이 브랜치에 복사하지 않았다. 사용자 인계에 따르면 Expo PR #127(`2253a4e`)의 nullable place/255 Unicode codepoints/trim/revision 승인 및 현재 장소·신청 당시 장소 구분을 수용했다. 통합 시 #127 장소 필드와 검증을 보존하고 #126 시간 검증, #125 후속 경로 원장, #128 묶음 처리를 순서대로 조정한다. 공급자 PR 병합·실연동 및 Notification #28 실제 승인·발송은 별도 보류다.

## 순차 검증 재개

사용자의 순차 재개 지시로 이전 중단 지시를 대체한다. 검증 순서는 #127(사용자 인계: 47클래스 489/489 통과, 프로세스 종료) → #125 → #126 → #128이다. 이 순서는 위 기능 통합 제안과 별개다. 이전 병행 실행에서 `ktlintCheck`·`spotlessCheck`는 통과했지만 테스트·전체 build는 중단되어 미확인이었다. 이번에는 기존 컴파일 산출물을 재사용해 좁은 회귀 테스트 후 전체 CI 명령을 1회 수행한다. #125 프로세스 종료를 확인한 다음 #126 기존 터미널에만 인계한다.

공급자 develop `a3f541faa86a69b337d0bc4d8a84e5558a8c12d9`의 `ProgramPolicyRequest`와 두 이슈 본문·댓글을 재확인했다. Expo #123은 여전히 OPEN/댓글 없음이며 경로별 정원 스키마는 없다. 기능 보류 판단을 유지한다.

후속 구현은 확정된 경로를 서버에서 결정하고 원장에 불변으로 보존한 뒤, 동일 잠금 안에서 해당 경로 및 직업 상한을 검증해야 한다. 동시 마지막 좌석, 다른 경로 동시 신청, 기존 중복/자격, 취소/재시도, 미상 legacy 행, 경로 위조, 승인된 대기·승급 정책을 회귀 테스트해야 한다. 최신 develop을 다시 반영하고 아래 검증을 재실행한 다음 배포하며, 계약 확정 전 경로별 좌석 기능을 활성화하지 않는다.

## 검증 명령

```sh
./gradlew test --tests 'team.startup.application.ProgramSessionApplicationTests' --tests 'team.startup.application.ProgramSessionPublicHttpTests' --no-daemon
./gradlew build ktlintCheck spotlessCheck --no-daemon
git diff --check
```

## 이번 순차 검증 결과

- 좁은 회귀: 위 두 클래스 66/66 통과(62+4), 실패/오류/스킵 0, Gradle SUCCESS 29초. 기존 Kotlin/Java/test 컴파일 산출물을 재사용했다.
- 전체 CI: 위 전체 명령을 이번 순차 검증에서 1회 실행, SUCCESS 7분 25초. XML 47클래스 482/482 통과, 실패/오류/스킵 0. #127 인계의 489개는 다른 워크스페이스 결과이며 이 브랜치의 수치와 섞지 않는다.
- `git diff --check` 통과. 변경은 OpenAPI 설명 1곳과 이 문서이며 신규 기능·DB 변경은 없다.
- 종료 시 Hikari의 닫힌 연결/연결 거부 경고가 있었으나 Gradle exit 0 및 XML 실패/오류 0을 확인했다. 이 경고를 이번 범위에서 수정하지 않았다.
- 좁은 실행 wrapper `22762`/daemon `22791`/worker `23051`, 전체 실행 wrapper `24179`/daemon `24208`/worker `24563` 모두 `ps`에서 종료를 확인했다. 다른 작업자의 프로세스는 조작하지 않았다.
- 이전 병행 검증의 중단 기록은 이번 성공 결과와 구분한다. 계약 미확정 좌석 분리 기능은 여전히 보류이며, 검증 성공은 해당 기능의 완료 또는 실제 공급자 연동 성공을 뜻하지 않는다.
- 다음 검증 대상은 #126의 기존 `term_2d06fa3e-9aa0-422a-92ff-f45bd40117be`이며, #126 종료 확인 후 #128 기존 `term_6c242fb4-2035-4435-b07b-7159e311f3f4`로 인계한다. 커밋·푸시·PR 생성·머지·이슈 종료는 수행하지 않았다.
