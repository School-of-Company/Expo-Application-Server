# Application #28: 연수 신청 작업 영수증과 변경 버전

## 조사 기준과 책임

- Application 시작 HEAD: `f24115c3d4760f88a39945ff7bcaa72e849b93e2`, 기준 브랜치 `origin/develop`.
- [Application #19](https://github.com/School-of-Company/Expo-Application-Server/issues/19)는 PR #24로 병합됐다. ADD는 추가 신청, REPLACE는 연수자의 전체 신청 교체다. 빈 목록은 전체 취소이며 포함된 기존 행은 유지한다. 행사별 부분 교체로 바꾸지 않는다.
- [Expo #44](https://github.com/School-of-Company/Expo-Expo-Server/issues/44)의 SMS 구현은 [PR #57](https://github.com/School-of-Company/Expo-Expo-Server/pull/57)로 병합됐다. 조사 SHA는 `27bbc5a3b5d255e3088988b1c6c18216d06c2400`이다.
- 해당 SHA의 `TrainingDependenciesClient.apply/replaceApplications`는 operationId 없이 각각 201/204만 확인한다. 단건·다건 ADD, 등록 후 REPLACE, 프로그램 DELETE 모두 기존 Application 경로를 사용한다. User가 연수자를 생성·재사용하고 Expo가 프로그램 존재·소속·분류를 검증한다.
- 같은 SHA의 `TrainingApplicationSmsServiceImpl`은 `ADD/REPLACE + 정렬된 프로그램 ID` 지문으로 Expo outbox를 준비하고 원격 성공 응답 뒤 confirmed로 전환한다. 이 지문은 취소 후 재신청이나 원격 성공 직후 응답 유실의 증거가 될 수 없다. 이번 변경만 배포해도 기존 Expo가 새 계약을 사용하게 되지는 않는다.
- [Notification #8](https://github.com/School-of-Company/Expo-Notification-Server/issues/8)은 기존 `notification.sms.requested` CUSTOM 이벤트를 가짜 sender로 검증하는 후속 작업이다. Expo가 eventId/본문/실제 전화번호를 제공한다. Kafka ACK는 SMS 전달 완료가 아니며 Application 영수증도 SMS 성공 증거가 아니다.
- Application은 신청·버전·작업 영수증만 소유한다. SMS/Solapi/Kafka 호출, 전화번호 및 문자 저장은 추가하지 않는다.

## 구현한 Application 계약

기존 경로·필드·상태·빈 응답은 유지한다. 새 필드는 선택적이다.

```http
POST /internal/training-program-applications
X-Internal-Token: <service token>
Content-Type: application/json

{"trainee":{"id":7,"expoId":"expo-1"},"programs":[{"id":10,"expoId":"expo-1","category":"CHOICE"}],"operationId":"8b350264-ce36-4f41-812a-327e3e998f30","expectedVersion":0}
```

ADD는 최초 성공과 같은 작업 재전달 모두 **201, 빈 본문**이다. REPLACE는 기존 `PUT /internal/training-program-applications/trainee/{traineeId}`에 같은 선택 필드를 받고 최초 성공·재전달 모두 **204, 빈 본문**이다. 기존 무키 ADD의 중복·정원·삭제 충돌은 계속 409다. 요청 검증은 400, 인증 실패는 401이다.

`operationId`는 UUID이며 Application 연수 작업 전체에서 전역 유일하다. 같은 ID의 같은 성공 명령은 다시 실행하지 않는다. 다른 명령이면 409다. 명령 비교에는 ADD/REPLACE, traineeId, trainee expoId, 프로그램별 ID/expoId/category, expectedVersion을 포함한다. 프로그램 순서는 정렬해 비교하되 중복 ID는 정규화로 감추지 않고 400으로 거절한다. UUID 표기 차이는 UUID 값으로 통일한다.

명령 저장 형식은 `[1, operationType, traineeId, expoId, expectedVersion, [[programId, programExpoId, category], ...]]`이다. 첫 값 1은 형식 버전이며 프로그램 참조는 ID 순서다. 이 고정 필드의 문자열·정수·null 배열만 전용 기본 JSON mapper로 직렬화한다. HTTP용 mapper 설정과 DTO 전체 직렬화를 사용하지 않으므로 DTO에 선택 필드가 추가되거나 HTTP 출력/포함 정책이 변경돼도 기존 작업 비교값은 유지된다. 향후 저장 형식을 변경한다면 보존 중인 이전 형식의 비교 지원도 유지해야 한다. 이 형식은 아직 배포되지 않은 이번 신규 계약에 적용한다.

```http
GET /internal/training-program-applications/operations/8b350264-ce36-4f41-812a-327e3e998f30
X-Internal-Token: <service token>

200
{"operationId":"8b350264-ce36-4f41-812a-327e3e998f30","operationType":"ADD","expoId":"expo-1","traineeId":7,"version":1,"changed":true,"programIds":[10],"completedAt":"<UTC Instant>","status":"SUCCEEDED"}
```

영수증은 commit된 성공의 불변 스냅샷이다. `programIds`는 작업 직후 연수자의 전체 프로그램 ID를 정렬한 목록이고 `version`은 그 시점의 변경 버전이다. `completedAt`은 트랜잭션 안에서 영수증을 만든 서버 시각이며 DB commit 시각은 아니다. 이후 취소·삭제·재신청해도 영수증을 고치지 않는다. 과거 성공 재전달은 당시 HTTP 성공만 재사용하며 현재 신청을 복원하거나 버전을 올리지 않는다.

조회 결과 **404는 commit된 성공 영수증 없음**만 뜻한다. 진행 중, 한 번도 호출되지 않음, 검증 실패, 롤백을 구분하지 않는다. 조회는 진행 중 쓰기의 commit을 기다리는 API가 아니다. 404를 실패나 SMS 발행 근거로 취급하지 않는다. 명령의 400/409는 해당 시도의 명시적 거절이며 실패 영수증은 저장하지 않는다. 실패 키도 예약하지 않으므로 복구 호출자는 항상 같은 ID와 같은 명령을 보존해야 한다.

`operationId`가 없는 기존 요청에는 영수증을 생성하지 않는다. 성공 영수증과 멱등 기록은 현재 자동 만료·삭제하지 않는다. 유한 보존 기간이나 정리 기능은 Expo 복구 기간 합의 전 도입하지 않는다. 이는 양 서비스 간 보존 기간 합의를 완료했다는 뜻은 아니다.

## 버전·삭제·취소·늦은 호출

```http
GET /internal/training-program-applications/trainee/7/version
X-Internal-Token: <service token>

200 {"version":1}
```

버전 범위는 **traineeId의 전체 신청 집합**이다. #19 교체 범위와 일치하며 expo별 버전으로 임의 분할하지 않는다. 버전 행이 없으면 0이다. User의 연수자 존재 여부를 이 조회로 검증하지 않는다. 마이그레이션 시 기존 연수자도 0부터 시작하며 과거 변경 횟수를 복원하지 않는다. 배포 뒤 모든 Application 변경 경로에서 적용한다.

| 경로 | 버전 정책 |
| --- | --- |
| ADD (기존 무키 포함) | 성공 시 한 번 +1, 실패 시 변화 없음 |
| REPLACE (기존 무키 포함) | 실제 프로그램 집합이 달라질 때만 +1 |
| 같은 집합 REPLACE | 성공, changed=false, 버전 유지 |
| 빈 목록 REPLACE | 기존 행이 있으면 취소 +1, 이미 비었으면 유지 |
| 프로그램 DELETE | 실제 신청을 제거한 각 연수자에게 한 번 +1 |
| 재삭제/신청이 없는 프로그램 DELETE | 삭제 마커 유지, 연수자 버전 변화 없음 |
| 같은 성공 operationId 재전달 | 원본 성공 재사용, 현재 상태/버전 변화 없음 |
| 취소 후 새 ADD | 새로운 operationId와 증가한 새 버전; 같은 프로그램 ID 집합이어도 새 변경 |

프로그램 DELETE는 영구 삭제 마커를 남기므로 삭제된 프로그램 ID로 새 ADD/REPLACE는 계속 409다. 전체 취소는 프로그램 삭제가 아니므로 같은 ID로 새 신청할 수 있다. 기존 출석 필드/신청 ID 유지 정책은 변경하지 않는다. 출석 변화는 신청 집합 버전의 범위가 아니다. DB 직접 수정은 이 계약을 우회하므로 운영 변경도 서비스 경로를 사용해야 한다.

`expectedVersion`은 operationId가 있을 때만 사용할 수 있는 0 이상 정수다. 성공 영수증 재전달 판별을 먼저 수행하므로 원래 성공의 기대 버전이 현재보다 오래됐어도 같은 성공을 반환한다. 아직 성공하지 않은 명령의 기대 버전이 현재와 다르면 409이며 영수증이 생기지 않는다. 같은 기대 버전으로 실제 변경하는 경쟁 명령 중 한 건만 성공한다. 실제 변경 없는 명령은 버전을 소비하지 않는다.

기대 버전을 생략하면 기존 직렬 실행 정책을 따른다. 아직 성공하지 않은 오래된 REPLACE가 나중에 도착하면 최신 상태를 교체할 수 있다. 따라서 선택 필드만 제공하는 이번 구현이 모든 늦은 호출을 차단한다고 설명하지 않는다. Expo의 fencing 적용 여부와 범위는 아래 합의가 필요하다.

## 원자성과 잠금

신청 변경, 버전 갱신, 성공 영수증 INSERT는 동일한 공개 서비스 메서드의 `@Transactional`과 동일 PostgreSQL 연결에서 처리한다. 영수증 INSERT 실패도 신청 저장/삭제 및 버전을 모두 롤백한다. 외부 네트워크 호출은 이 트랜잭션에 추가하지 않았다.

키가 있으면 별도 두 정수 advisory namespace `(28, hashtext(operationId))`를 먼저 잠근다. 해시 충돌은 무관한 작업을 직렬화할 뿐, 실제 UUID PK/전체 명령 비교를 대체하지 않는다. 이후 기존 **연수자 → 정렬된 프로그램** advisory 잠금을 유지하고 마지막에 연수자 버전 행을 잠근다. DELETE는 기존 프로그램 잠금과 삭제 마커를 유지하고 영향 연수자의 버전 행을 ID 오름차순으로 잠근다. DELETE가 연수자 advisory 잠금을 프로그램 뒤에 획득하지 않으므로 역순 교착을 만들지 않는다. 어떤 경로도 버전 행 잠금 뒤에 추가 프로그램 잠금을 얻지 않는다.

조회 API도 `/internal/**`의 기존 `X-Internal-Token` 필터를 사용한다. 전화번호·본문은 영수증에 포함하지 않고 SQL 입력은 파라미터로 바인딩한다.

## Expo와 결정해야 할 사항 (미확정)

아래 소비자 결정·구현·통합 검증은 [Expo #64](https://github.com/School-of-Company/Expo-Expo-Server/issues/64)에 후속으로 등록했다. Notification 검증·배포는 기존 [Notification #8](https://github.com/School-of-Company/Expo-Notification-Server/issues/8)을 사용하며 중복 이슈를 만들지 않는다. Application #28의 구현 완료와 Expo 소비자 전환 완료는 각각 소유 서비스에서 처리한다.

1. **작업 키 생성·저장 시점:** Expo가 최초 Application 호출 전에 operationId/정규화 명령을 내구 저장해야 한다. 기존 SMS 지문이나 현재 신청 목록으로 키를 추정하지 않는다. 공개 API 요청 필드/본인 인증 변경 없이 새 사용자 작업과 재전달을 어떻게 구분할지 Expo가 결정해야 한다. Application UUID와 Notification eventId를 동일하게 쓸지 매핑할지도 미확정이다.
2. **소비 대상:** 다건 ADD·등록/전체 REPLACE가 영수증 소비 대상인지 확정하고, SMS 대상이 아닌 단건 ADD에도 키/fencing을 쓸지 정한다. 기존 단건 호출자도 버전은 갱신하므로 다건 작업의 기대 버전에 영향을 준다.
3. **성공 복구:** Expo의 PREPARED/불확정 작업은 같은 키로 조회·재전달한다. 200 영수증에 대해 expoId/traineeId/type을 검증한 뒤 confirmed로 전환하고 404만으로 SMS를 발행하지 않는다. 재시도 간격·상한·운영 개입, 명시적 실패 저장 필요 여부, 진행 중/실패를 별도 조회 상태로 노출할 필요를 정한다.
4. **늦은 호출과 fencing:** expectedVersion을 언제 조회·내구 저장하며 어떤 호출에 필수로 적용할지 정한다. 조회 뒤 경쟁 변경이 있으면 409를 사용자에게 돌릴지 새 작업으로 재승인할지 정한다. 오래된 명령의 기대 버전을 자동 최신화하면 fencing을 무력화하므로 그렇게 구현하지 않는다.
5. **변경 없는 성공의 SMS:** changed=false, 빈 목록 취소, 이미 취소/삭제된 과거 성공을 복구할 때 SMS를 보낼지/보류할지 Expo가 결정한다. 영수증은 과거 성공과 변경 여부를 제공할 뿐 최신성에 따른 문자 정책을 결정하지 않는다.
6. **보존·재생:** Application은 현재 성공 기록을 무기한 유지한다. 유한 기간, 만료 후 키 재사용/410 여부, Expo 작업/outbox 보존·재처리 기간을 합의해야 정리 기능을 넣을 수 있다. Notification #8에 기록된 develop 24시간/PR #7 7일 완료 마커와 DLQ 재생 기간·실제 배포 버전을 따로 확인한다. Application 키 보존이 Notification 중복 억제 기간을 늘려 주지는 않는다.

## 의존성·배포·#20 병행 작업

- Application PR #24/#25/#27/#29는 시작 HEAD에 이미 병합돼 있다. 이번 코드에 추가로 필요한 미병합 선행 Application PR은 확인되지 않았다. Expo PR #57도 병합됐지만 기존 무키 소비자다. Notification PR #7의 실제 배포 여부는 이 워크스페이스에서 확정하지 않는다.
- 새 Flyway `V4_1__training_operation_receipts.sql`은 별도 두 테이블만 생성한다. 기존 V1/V2/V3와 출석 엔티티/DTO/컬럼은 수정하지 않는다. #20 워크스페이스는 읽기 전용으로 마이그레이션 파일명만 확인했고 아직 새 migration이 없었다.
- #20이 추가할 migration과 **번호 및 적용 순서**를 병합 전에 다시 대조한다. 특히 V4가 V4.1 배포 뒤 도착하면 기본 Flyway 설정에서 건너뛰거나 검증 오류가 생길 수 있으므로, 나중에 배포할 미적용 migration은 더 높은 번호를 정한다. 이미 적용된 파일은 재번호하지 않는다.
- Application 마이그레이션/모든 쓰기 인스턴스를 먼저 배포하고 기존 Expo 201/204/409 회귀를 확인한다. 구버전 Application 인스턴스는 버전을 갱신하지 않으므로 혼합 버전 중 Expo의 기대 버전 소비를 활성화하지 않는다.
- 위 결정 사항 합의 → Expo 키/명령 내구 저장·영수증 복구·변경 식별 전환 구현 → 두 서비스 통합 검증 순서로 진행한다. Application 배포만으로 Expo #28 소비가 완료됐다고 처리하지 않는다.
- SMS 활성화는 별도다. Notification #8의 가짜 sender/Kafka·Redis/consumer group 초기 offset·TRAINEE 설정 검증 및 행사별 문구 정책 확인이 선행한다. 실제 Solapi 유료 발송은 이번 작업에서 금지된다.

## 검증

`TrainingProgramApplicationHttpTests`에 기존 무키 상태·출석·정원·교체·삭제 계약과 함께 실제 PostgreSQL 기반 키 동시 경합, 다른 명령 충돌, 기대 버전 경합/늦은 호출, 취소 후 재신청, 삭제 후 과거 영수증 보존, 변경 없음, 실패 후 재시도, 영수증 저장 실패 시 원자적 롤백을 검증한다. HTTP 프록시가 upstream commit 뒤 응답을 끊는 경우도 영수증 조회/동일 키 재시도로 복구한다. 테스트 외부 서비스는 호출하지 않는다.

2026-10-07 최종 검증:

- 전용 임시 PostgreSQL 17을 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`로 제공하고 `EUREKA_CLIENT_ENABLED=false`로 설정해 `./gradlew ktlintFormat spotlessApply build ktlintCheck spotlessCheck --no-daemon` 실행: **BUILD SUCCESSFUL, 47초**.
- 전체 **69개 성공, 실패/오류/건너뜀 0개**. 연수 HTTP 계약 테스트는 28개이며 실제 응답 단절 복구와 99,999명 프로그램 삭제를 포함한다. 테스트 케이스 실행 시간 합계는 약 29초다.
- `git diff --check` 통과. 새 조회 경로의 토큰 인증, 파라미터 바인딩, 민감정보 미포함을 소스와 HTTP 테스트로 확인했다.
- 별도 PostgreSQL 스키마에 V1~V3.1을 적용하고 기존 신청 3행을 넣은 뒤 V4.1 실행: 연수자 2명의 버전은 각각 0, 기존 신청 ID 81/82/83 및 출석 값이 보존됨을 확인했다. 해당 검증은 롤백했고 전용 임시 컨테이너도 제거했다.
- DB 설정 없이 처음 실행한 전체 빌드는 기존 `ExpoApplicationServerApplicationTests.contextLoads`에서 `Failed to determine a suitable driver class`로 실패했다. 이 테스트는 Testcontainers를 선언하지 않아 DB 설정이 필요하다. 코드나 테스트를 약화하지 않고 전용 DB 제공으로 해결했다. 저장소 CI는 local 프로필과 compose DB를 제공한다.
- 커밋·푸시·PR·머지 및 실제 SMS/Solapi 발송은 수행하지 않았다. Expo 소비자 전환과 Notification 실연동은 위 미확정 결정 사항 및 배포 조건이 남아 있다.

2026-10-07 Claude LOW 리뷰 반영 후 검증:

- HTTP mapper의 들여쓰기/`NON_NULL` 설정 변경 뒤 성공 작업 복구가 기존 코드에서 409로 실패함을 회귀 테스트로 재현했다.
- 비교 명령을 위 버전 1 고정 필드 형식과 전용 mapper로 분리했다. 회귀 테스트는 저장 문자열의 고정 형식과 설정 변경 뒤 replay 성공, 신청 수/버전 불변을 검증한다.
- 전용 임시 PostgreSQL 설정으로 같은 Gradle 전체 검증 명령 재실행: **BUILD SUCCESSFUL, 53초**. 전체 **70개 성공, 실패/오류/건너뜀 0개**, 연수 HTTP 테스트 29개. ktlint·spotless 및 `git diff --check` 통과.
- #20 마이그레이션 번호·배포 순서 합의는 여전히 병합 전 확인 사항이다. 다른 워크스페이스를 수정하거나 커밋·푸시·PR·머지하지 않았다.
