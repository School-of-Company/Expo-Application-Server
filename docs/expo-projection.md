# Expo 로컬 프로젝션 (`expo.expo.changed`)

관련: Expo [#212](https://github.com/School-of-Company/Expo-Expo-Server/issues/212), 발행 계약은 Expo `docs/expo-change-events.md`(Expo PR #213).

Expo가 박람회 변경마다 발행하는 전체 상태 이벤트를 받아 Application DB에 읽기 전용 복사본을 두고, 관리자 그룹 발송의 박람회 제목 조회만 그 복사본으로 바꾼다. 기본은 꺼져 있고, 꺼져 있으면 소비자·프로젝션 읽기가 아예 뜨지 않아 지금과 같다.

## 무엇을 옮기고 무엇을 남기나

프로젝션으로 읽는 값(켜져 있을 때)은 `GET /internal/expo/{id}`의 `title` 하나다(`AdminGroupMessageGateway.expoName`, 관리자 그룹 발송 `#{행사명}`). 표시용이라 이벤트 반영이 늦거나 실패해 옛 제목이 나가도 판단이 틀어지지 않는다.

동기로 남기는 호출:

- 박람회 기간·전화번호 인증 설정 `GET /internal/expo/{id}`(`RegistrationGateway.expoPeriod`)와 사전등록 회차 취소 기한 `GET .../preregister-sessions/{sid}`(`OwnerCancellationDeadlineGateway.preregister`): 접수·취소 허용 판단이라 반영이 늦거나 실패한 옛 값을 쓰면 안 된다. 플래그와 관계없이 매번 Expo에 묻고, 실패하면 예전처럼 503이다. 소비자는 기간·인증 설정·회차 취소 기한도 저장하지만 지금은 읽지 않는다.
- 관리자 권한 확인 `admin-access`, `admin-application-{cancel,promote}-access`: 인증·권한이라 매번 Expo에 묻는다.
- 일반참가자 집계 `PUT .../standard-registrations/{participantId}`: Expo를 바꾸는 명령이다.
- 프로그램 정의 `GET .../standard-programs/{id}`, `POST .../training-programs/batch`(조회지만 POST): 정원·승급·신청·취소 판정에 들어가고, 이벤트에 없는 `pendingRevision`(변경 중이면 409)과 승급 안내의 `applicationLookupUrl`이 필요하다. 오래된 정원으로 초과 접수할 수 있어 강한 일관성이 필요하다.
- 사전등록 회차 정의 `GET .../preregister-sessions/{sid}`(`PreregisterSessionGateway`): 정원 판정과 회차 변경 승인 원장 대조에 쓰여 오래된 값이면 초과 접수나 잘못된 409가 난다.
- 관리자 현재 회차 목록 `GET .../program-sessions`: Expo가 계산하는 `definitionVersion`과 페이지가 이벤트에 없다.

## MISS와 대체

프로젝션에 박람회 행이 없거나, tombstone이거나, `expo.deleting`이 참이면 MISS다. MISS면 기존 동기 호출(회로 차단기 포함)을 그대로 부르고, 같은 대상의 MISS는 INFO 로그 `Expo projection miss, falling back to sync call`을 한 번만 남긴다. 그래서 켠 직후 republish 전에도 동작은 지금과 같다.

## 소비자

- 토픽 `expo.expo.changed`, 키 `expoId`. 그룹 `expo-application-server.expo-changed`(`KAFKA_EXPO_PROJECTION_GROUP_ID`로 바꿀 수 있지만 바꾸면 남은 이벤트를 다시 읽는다). 오프셋이 없으면 `earliest`.
- 박람회마다 마지막 `version`을 `tb_expo_projection`(V44)에 두고, 그 이하인 메시지(같은 `eventId` 재전송 포함)는 아무것도 바꾸지 않는다. 더 새 메시지는 박람회 행과 회차 행을 한 트랜잭션에서 통째로 덮어쓴다.
- tombstone(`deleted: true`)은 회차 행을 지우고 박람회 행은 `deleted`로 남겨 version을 기억한다.
- Expo는 `expo_id`를 VARCHAR(36)로 둔다. 표준 UUID 문자열(대소문자 무관)이고 레코드 키와 같아야 하며, Application은 `uuid`로 저장한다.
- 저장하는 값은 위 표의 값과 `deleting`뿐이다. 프로그램 배열은 읽지 않는다.
- 재시도와 DLT는 Expo StandardCreated 소비자와 같다. 일시적 DB·연결 오류만 1·2·4·8·10초로 다시 시도하고, 잘못된 메시지(`schemaVersion` ≠ 1 포함)와 그 밖의 실패는 `expo.expo.changed.dlt`로 바로 보낸다. DLT 발행이 실패하면 오프셋을 넘기지 않는다. 로그에는 파티션·오프셋·예외 종류만 남긴다.

## 켜는 순서

1. 운영 Kafka에 `expo.expo.changed`와 `expo.expo.changed.dlt`를 만들고, 소스 읽기·그룹 권한과 DLT 쓰기 권한을 준다.
2. `KAFKA_EXPO_PROJECTION_ENABLED=true`, `KAFKA_EXPO_PROJECTION_BOOTSTRAP_SERVERS`를 설정해 배포한다. 켜고 bootstrap이 없으면 시작을 거절한다.
3. Expo에서 `KAFKA_EXPO_EVENTS_ENABLED=true`인 상태로 `POST /internal/expo-events/republish`를 한 번 불러 전체 상태를 싣는다.
4. 다시 만들 때도 3을 반복한다. 재발행은 더 큰 version이라 그대로 덮어쓴다.

```sql
SELECT count(*) FILTER (WHERE NOT deleted) AS live, count(*) FILTER (WHERE deleted) AS tombstones, max(applied_at)
FROM tb_expo_projection;
```

## 테스트

- `ExpoProjectionReadTests`: 제목의 꺼짐·적중·MISS 대체와 기간·취소 기한의 항상 동기 조회를 Expo 모의 서버 호출 경로로 확인하고, 메시지 해석·거절 규칙을 본다(Docker 불필요).
- `ExpoProjectionKafkaTests`(Testcontainers PostgreSQL + Kafka): version 순서·중복·옛 메시지 무시, tombstone 뒤 되살림 방지, 삭제 중 MISS, 실제 broker 소비와 DLT, 제목은 프로젝션·기간은 동기인 게이트웨이 연결.
