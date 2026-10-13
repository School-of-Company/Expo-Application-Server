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

`FILTERED`는 화면 페이지가 아니라 같은 관리자 원장 필터의 현재 전체 결과이며 최대 5,000건이다. 실행과 재시도 요청은 미리보기와 같은 `source`, 64자리 소문자 SHA-256 `targetFingerprint`, 새 UUID `idempotencyKey`를 보낸다. Application은 현재 권한, 신청 상태, 참가자 이름, 대표자 번호, 렌더링 결과를 다시 계산한다. 하나라도 달라지면 `409`로 새 미리보기를 요구한다.

미리보기의 `applicationCount`는 중복 제거 전 현재 신청 원장 행 수, `recipientCount`는 발송 가능한 정규화 수신번호 수, `messageCount`는 발송 가능한 개별 문구 수다. `targetCount`와 `sendableCount`는 Notification 내부 계약 호환 필드이며 선택 신청 수로 해석하지 않는다. 누락 대상 수는 `missingCount`로 별도 제공한다.

SMS는 자유 본문을 받는다. 현재 Client 호환을 위한 최소 변수는 `{이름}`, `{프로그램}`, `{일시}`, `{장소}`, `{상태}`다. 이 이름과 일정 문자열 형식은 최종 운영 정책으로 확정된 것이 아니다. Application이 저장한 신청 시점 프로그램 스냅샷에 값이 없으면 임의 값을 만들지 않고 `TEMPLATE_DATA_MISSING`으로 미리보기에 표시한다. 특히 현재 프로그램 스냅샷에 장소가 없을 수 있다.

알려진 변수가 하나라도 있는 SMS는 신청별 개인 안내로 처리해 같은 대표자 번호의 다른 참가자와 신청 내용을 모두 보존한다. 알려진 변수가 없는 SMS는 공통 안내로 처리해 숫자만 남긴 같은 대표자 번호당 한 건만 만든다. 일반과 연수 모두 신청 원장의 `participantId`를 User의 일반 참가자로 조회하고 `representativeId ?: participantId` 참가자의 `phoneNumber`만 수신 번호로 쓴다. 개인 번호나 `notificationPhoneNumber` fallback은 대표자 번호를 대신하지 않는다.

카카오는 Notification의 `/internal/admin-group-messages/config`가 활성화한 승인 템플릿만 선택할 수 있다. Application은 SMS 자동 대체를 항상 `false`로 전달한다. 실제 카카오 설정이 없으면 `enabled=false`, 빈 템플릿 목록이며 발송은 `422`다.

Application은 전화번호와 문구를 저장하지 않는다. Notification 미리보기와 작업이 멱등 상태, 성공/실패/결과 불명, 재시도 금지를 소유한다. 서비스 간 경로에는 각 서비스의 `X-Internal-Token`이 필요하다. 송신 토큰은 `APPLICATION_ADMIN_GROUP_MESSAGE_{EXPO,USER,NOTIFICATION}_INTERNAL_TOKEN`으로 대상마다 따로 설정하며 각각 Expo `EXPO_INTERNAL_TOKEN`, User `INTERNAL_TOKEN`, Notification `ADMIN_GROUP_MESSAGING_INTERNAL_TOKEN`과 같아야 한다. `APPLICATION_INTERNAL_TOKEN`으로 대체하지 않으며, URL이 설정된 대상의 토큰이 비어 있으면 기동이 실패한다.

Notification 실제 HTTP 계약은 fake 공급자와 Redis를 띄운 뒤 다음 독립 Kotlin 검사로 확인한다. 이 검사는 설정, 미리보기 수량, 실행 멱등성, 완료 상태, 성공 건 재시도 금지를 실제 내부 경로에서 확인한다.

```bash
GROUP_MESSAGE_NOTIFICATION_CONTRACT_URL=http://127.0.0.1:PORT \
GROUP_MESSAGE_NOTIFICATION_CONTRACT_TOKEN=shared-test-token \
kotlinc -script scripts/check-admin-group-notification-contract.main.kts
```
