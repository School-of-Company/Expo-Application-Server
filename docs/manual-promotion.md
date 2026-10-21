# 자동 승급 종료와 관리자 수동 확정 (#172)

Expo #143(브랜치 `feat/143-promotion-auto-end-manual-confirm`, 커밋 623cb73) 계약을 소비한다. 대상은 일반·연수 프로그램 회차 대기다. 사전등록과 현장 접수에는 새 대기 운영을 추가하지 않는다.

## 정책

회차 `policy`의 선택 필드다. Expo는 값이 없으면 키를 보내지 않으며, 구형 정의는 그대로 기존 동작이다.

| 필드 | 없음(기본) | 의미 |
| --- | --- | --- |
| `autoPromotionEnabled` | true | false면 자동 승급을 하지 않고 수동 확정만 받는다 |
| `autoPromotionEndedAt` | `promotionEndedAt`까지 자동 | 이 시각(포함)부터 자동 승급을 멈춘다 |
| `promotionEndedAt` | - | 자동·수동 모두의 최종 마감(`now < promotionEndedAt`) |

- 자동 승급 조건은 `ProgramSessionPolicy.autoPromotionOpen` 하나다. 취소·신청·스케줄러·정의 변경 재조정·학생/보호자 묶음·#144 참여 제안 생성이 모두 `ProgramWaitlist.promote`를 거치며, 단건·묶음의 확정 직전 재확인도 같은 조건을 쓴다. 기존 조건(`closed`, 모집 시작, `applicationEndedAt`, `promotionEndedAt`)은 그대로 유지한다.
- 수동 확정은 `manualPromotionBlock`이 null일 때만 연다. 정책이 자동 승급을 끄거나 `autoPromotionEndedAt`이 지난 뒤부터 `promotionEndedAt` 전까지이며, `closed`이면 막는다. 기본 정책에는 수동 확정 구간이 없다. 날짜는 저장 정책에서만 읽고 코드에 고정하지 않는다.
- 자동 승급이 멈춰도 대기는 취소하지 않는다. 새 신청은 기존처럼 대기가 있으면 대기 뒤에 선다.

## 진행 중 참여 제안 (#144)

- 자동 승급이 멈추면 새 제안을 만들지 않는다. 거절·만료 뒤 다음 FIFO 대상에게 제안하는 것도 같은 조건으로 멈춘다.
- 이미 만든 제안은 확보 좌석과 응답 기한(`min(applicationEndedAt, promotionEndedAt)` 기준)을 그대로 유지한다. 자동 종료를 이유로 무효화하지 않는다. 대표자가 수락하면 기존 #144 경로로 확정된다.
- 수동 확정은 다른 제안이 확보한 좌석을 쓰지 않는다. 대상(묶음이면 구성원 중 하나)에 활성 제안이 있으면 409 `OFFER_ACTIVE`다. 그 제안이 수락·거절·만료로 끝난 뒤 처리한다.

## 관리자 API

`POST /application/admin/expos/{expoId}/program-session-applications/{applicationId}/promotion`

- 원본 `Authorization: Bearer` 관리자 JWT를 Expo `GET /internal/expo/{expoId}/admin-application-promote-access`로 매번 검증한다. 응답은 `permission=APPLICATION_PROMOTE`, `policyVersion=EXPO_ADMIN_APPLICATION_PROMOTE_V1`, `scope=EXPO_ADMIN`이어야 한다. `APPLICATION_READ`·`APPLICATION_CANCEL`로는 열리지 않는다. Expo 401/403/404는 그대로 전달한다. Expo가 행사 권한 행이 없다고 거절한 403(`수동 확정 권한이 없습니다.`)만 본문 `{"status":403,"code":"APPLICATION_PROMOTE_FORBIDDEN"}`로 구분한다(#267). 다른 401/403/404는 상태만 전달하고 Expo 문구·code를 싣지 않는다.
- 200 `{"applicationId", "status":"CONFIRMED"}`, `Cache-Control: no-store`. 이미 확정된 신청은 같은 결과를 돌려주며, 이력을 새로 만들지 않는다.
- 404는 신청이 없거나 다른 행사 신청인 경우다. 409 reason: `AUTO_PROMOTION_ACTIVE`, `PROMOTION_ENDED`, `NOT_WAITING`, `OFFER_ACTIVE`, `CAPACITY_CHANGED`, `TIME_CONFLICT`, `PREREGISTRATION_NOT_CONFIRMED`, `GROUP_ALREADY_APPLIED`, `TRAINEE_UNAVAILABLE`, 묶음 자격 사유 등. 프로그램 삭제·정의 변경 중이면 기존 409다. 잠금 경합·공급자 장애는 503이다. 실패해도 대기는 그대로 둔다.
- 운영자가 고른 대기를 확정하며 FIFO 선두일 필요는 없다. 자동 승급과 같은 잠금(참가자 → 일정·묶음·프로그램) 아래에서 정원, 사전 좌석(#125), 채널 정원, 활성 제안 확보 좌석, 사전등록 확정, 시간 충돌, 연수자 연결·기존 묶음, 삭제·`closed`, 최종 마감을 다시 확인한다. User·Expo 호출 뒤에도 최종 마감을 한 번 더 확인한다.
- 학생·보호자 묶음은 구성원 하나를 지정해도 묶음 전체를 확정하거나 전부 대기로 둔다. 묶음 자격은 기존 승급 검증(`PROMOTION`)을 쓴다.
- 이력: `tb_program_session_history`에 `actor_type=ADMIN`, `actor_id`=검증된 관리자 ID, `previous_status=WAITING`, `created_at`을 남긴다. 확정 이벤트·승급 알림(V15)·관리자 SSE(V28)·대기 수·잔여석은 기존 확정 경로를 그대로 따른다. 새 발송 체계는 없다.

## 지원 신호와 정책 변경

- `GET /internal/expos/{expoId}/program-policy-capabilities`, `PUT …/program-changes/{changeId}`, `PUT …/program-changes/{changeId}/guardian-policy`의 204에 `X-Program-Promotion-Policy-Version: 1`을 붙인다. Expo는 이 헤더가 없으면 기본값이 아닌 설정을 503으로 거부한다.
- 변경 승인은 Expo와 같은 형식만 받는다: `applicationStartedAt ≤ autoPromotionEndedAt ≤ promotionEndedAt`, 마이크로초 정밀도, 자동 OFF와 종료 시각 동시 지정 불가, `waitingCapacity=0`이면 OFF·종료 시각 불가. 어긋나면 400이다.
- 신청이 있는 회차에서도 자동 승급 설정 변경(끄기·다시 켜기·종료 시각 앞당김/늦춤)은 409로 막지 않는다. 대기를 취소하거나 확보 좌석을 풀지 않으며, 다시 켜면 다음 승급 계기부터 FIFO 자동 승급이 재개된다. 정원·대기 정원 축소 규칙은 기존대로다.

## 배포

Expo #143 → Application(#172) → Client #642. Expo만 먼저 배포되면 기본 동작은 같고, 기본값이 아닌 설정은 이 Application이 배포될 때까지 Expo에서 503으로 막힌다. 롤백할 때는 기본값이 아닌 설정을 쓰는 회차가 없어야 한다. 이전 Application은 두 필드를 무시하고 계속 자동 승급한다.
