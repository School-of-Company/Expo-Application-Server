# 관리자 그룹 발송 Application 계약

Application은 Client가 전달한 관리자 Bearer JWT를 Expo의 `GET /internal/expo/{expoId}/admin-access`에 전달해 매 요청마다 현재 전역 관리자와 행사 상태를 확인한다. 현재 공급 계약은 `GLOBAL_ADMIN_APPLICATION_READ_V1`이며 행사 소유권 또는 별도 메시지 권한을 뜻하지 않는다. Application은 응답의 행사 ID, 양수 `adminId`, `APPLICATION_READ`, `GLOBAL_ADMIN`, 정책 버전을 모두 확인한다.

공개 경로의 기준은 `/application/expos/{expoId}/admin-group-messages`이다.

- `GET /config`: SMS와 카카오 승인 템플릿 사용 가능 상태
- `POST /previews`: 선택 접수 UUID 또는 현재 필터 전체의 대표자 번호, 누락 사유, 최종 문구 미리보기
- `POST /previews/{previewId}/jobs`: 권한과 대상을 재확인한 뒤 멱등 작업 실행
- `GET /jobs/{jobId}`: 진행, 실패, 결과 불명 상태 조회
- `POST /jobs/{jobId}/retries`: 권한과 대상을 재확인한 뒤 Notification이 확정한 실패 건만 재시도

미리보기 요청은 Client가 생성한 UUID `requestId`와 `source`를 받는다. `source.scope`는 다음 중 정확히 하나다.

```json
{"kind":"SELECTED","applicationIds":["application-uuid"]}
```

```json
{"kind":"FILTERED","filter":{"programType":"STANDARD","status":"CONFIRMED","sort":"CREATED_ASC"}}
```

`FILTERED`는 화면 페이지가 아니라 같은 관리자 원장 필터의 현재 전체 결과이며 최대 5,000건이다. `status`는 단일 문자열 또는 배열(`["CONFIRMED","WAITING"]`)이고 `eventDate`(제출 당시 회차 시작일, KST)를 받는다. 사람 필터 `q`·`occupation`은 적용할 수 없어 `400`이므로, 사람으로 좁힌 목록은 `SELECTED`로 보낸다. 실행과 재시도 요청은 미리보기와 같은 `source`, 64자리 소문자 SHA-256 `targetFingerprint`, 새 UUID `idempotencyKey`를 보낸다. Application은 현재 권한, 신청 상태, 참가자 이름, 대표자 번호, 렌더링 결과를 다시 계산한다. 하나라도 달라지면 `409`로 새 미리보기를 요구한다.

미리보기의 `applicationCount`는 중복 제거 전 현재 신청 원장 행 수, `recipientCount`는 발송 가능한 정규화 수신번호 수, `messageCount`는 발송 가능한 개별 문구 수다. `targetCount`와 `sendableCount`는 Notification 내부 계약 호환 필드이며 선택 신청 수로 해석하지 않는다. 누락 대상 수는 `missingCount`로 별도 제공한다.

SMS는 자유 본문을 받는다. 현재 Client 호환을 위한 최소 변수는 `{이름}`, `{프로그램}`, `{일시}`, `{장소}`, `{상태}`다. 이 이름과 일정 문자열 형식은 최종 운영 정책으로 확정된 것이 아니다. Application이 저장한 신청 시점 프로그램 스냅샷에 값이 없으면 임의 값을 만들지 않고 `TEMPLATE_DATA_MISSING`으로 미리보기에 표시한다. 특히 현재 프로그램 스냅샷에 장소가 없을 수 있다.

회차 장소(#127)는 [Expo #124 / PR #127](https://github.com/School-of-Company/Expo-Expo-Server/pull/127)의 `place: String?` 계약을 사용한다. 공급자는 최대 255 유니코드 문자와 앞뒤 공백 제거를 보장하며, 미입력은 null이다. Application은 정의·승인 payload·신청 스냅샷에 이 값을 보존하고 기존 revision/전체 정의 비교를 유지한다. 과거 JSON에는 필드가 없어도 되며 null 필드는 새로 직렬화하지 않는다. 기존 신청 스냅샷은 장소 변경이나 승급 시 덮어쓰지 않는다.

소유자 신청 응답의 `place`는 Expo 현재 장소, `snapshotPlace`는 신청 당시 장소다. 현재 프로그램이 삭제되면 현재 장소는 null이며 과거 장소로 대체하지 않는다. 관리자 원장의 `definition.place`는 신청 당시 장소이고, 최신 장소는 기존 `current-sessions`의 `items[].place`로 조회한다. 행사 전체 장소·사전등록 장소로 대체하지 않는다. 장소 미입력의 화면 문구 `장소 추후 안내`는 Client 표시 규칙이며 저장·문자 데이터가 아니다.

기존 안내는 신청 당시 장소를 사용한다. 화면의 최신 장소 정책을 문자에 임의 적용하지 않으며 새 템플릿 변수, 자동 재발송, 실제 발송을 추가하지 않는다. `place` absent/null/공백은 없는 값이고 장소가 필요한 안내는 기존 누락 처리, 장소가 필요 없는 안내는 기존 처리를 유지한다. 장소를 포함한 스냅샷이나 렌더링 결과가 달라지면 fingerprint가 바뀌어 새 미리보기가 필요하다. 카카오 장소는 Notification이 내려준 승인 `variableMapping.location`에만 연결한다. [Notification #28](https://github.com/School-of-Company/Expo-Notification-Server/issues/28)의 승인 설정·실제 발송 확인은 별도 선행 조건이다.

Expo PR #127과 Application #127은 배포 전 내부 정의 조회·변경 승인 호환성을 함께 확인해야 한다. Application 수용 코드를 먼저 배포한 뒤 Expo 장소 쓰기를 활성화한다. 다른 Application 작업의 정의 DTO/보호 로직(#125/#126), 소유자·관리자 조회/이력(#123/#124)와 겹칠 수 있으므로 먼저 병합된 변경을 반영하고 회귀 검증한다. 자세한 작업 상태와 검증 결과는 워크스페이스 코멘트에 남긴다.

알려진 변수가 하나라도 있는 SMS는 신청별 개인 안내로 처리해 같은 대표자 번호의 다른 참가자와 신청 내용을 모두 보존한다. 알려진 변수가 없는 SMS는 공통 안내로 처리해 숫자만 남긴 같은 대표자 번호당 한 건만 만든다. 일반과 연수 모두 신청 원장의 `participantId`를 User의 일반 참가자로 조회하고 `representativeId ?: participantId` 참가자의 `phoneNumber`만 수신 번호로 쓴다. 개인 번호나 `notificationPhoneNumber` fallback은 대표자 번호를 대신하지 않는다.

카카오는 Notification의 `/internal/admin-group-messages/config`가 활성화한 승인 템플릿만 선택할 수 있다. Application은 SMS 자동 대체를 항상 `false`로 전달한다. 실제 카카오 설정이 없으면 `enabled=false`, 빈 템플릿 목록이며 발송은 `422`다.

Application은 전화번호와 문구를 저장하지 않는다. Notification 미리보기와 작업이 멱등 상태, 성공/실패/결과 불명, 재시도 금지를 소유한다. 서비스 간 경로에는 각 서비스의 `X-Internal-Token`이 필요하다. 송신 토큰은 `APPLICATION_ADMIN_GROUP_MESSAGE_{EXPO,USER,NOTIFICATION}_INTERNAL_TOKEN`으로 대상마다 따로 설정하며 각각 Expo `EXPO_INTERNAL_TOKEN`, User `INTERNAL_TOKEN`, Notification `ADMIN_GROUP_MESSAGING_INTERNAL_TOKEN`과 같아야 한다. `APPLICATION_INTERNAL_TOKEN`으로 대체하지 않으며, URL이 설정된 대상의 토큰이 비어 있으면 기동이 실패한다.

Notification 실제 HTTP 계약은 fake 공급자와 Redis를 띄운 뒤 다음 독립 Kotlin 검사로 확인한다. 이 검사는 설정, 미리보기 수량, 실행 멱등성, 완료 상태, 성공 건 재시도 금지를 실제 내부 경로에서 확인한다.

```bash
GROUP_MESSAGE_NOTIFICATION_CONTRACT_URL=http://127.0.0.1:PORT \
GROUP_MESSAGE_NOTIFICATION_CONTRACT_TOKEN=shared-test-token \
kotlinc -script scripts/check-admin-group-notification-contract.main.kts
```
