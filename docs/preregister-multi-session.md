# 사전등록 여러 회차 (#198)

설계: `docs/expo-multi-session-design-2026-10-19.md`(박람회 문서 저장소) 2·3·4장, 5장 2-A.

## 규칙

- 확정 사전등록은 `(expo_id, session_id, participant_id)`마다 하나다(V33). 한 사람이 여러 회차를 가질 수 있다.
- 같은 사람의 확정 회차끼리는 시간이 일부라도 겹치면 안 된다. 겹침은 `startedAt < 다른 endedAt AND 다른 startedAt < endedAt`이며 입장 허용 시간(leadMinutes)은 넣지 않는다. 신뢰 ID 경로(`ApplyPreregisterSessionService`)는 409로 거절하고, User 저장 뒤 완료 단계에서 발견하면 기존 활성 신청 충돌처럼 `needsReview(409)`로 좌석을 유지한다.
- 회차 추가(공개 `addSession`과 신뢰 ID 경로)는 확정뿐 아니라 같은 번호의 다른 회차 `WAITING`·`RESERVED` 예약과 겹쳐도 409로 거절한다(#223). 시작했거나 마감돼 승급이 끝난 대기는 세지 않는다(`preregistration-waitlist-design.md` P12). 참석자가 정해진 예약(회차 추가)은 참석자가 겹칠 때만, 모르는 예약은 번호로 본다. 완료 단계의 겹침 검사는 확정만 그대로 본다(서로 대기·선점 중인 두 회차가 서로를 거절하지 않도록). 참여 제안 수락도 승급 처리 전에 같은 검사를 하며(다른 `WAITING`은 세지 않고 확정·`RESERVED`만) 겹치면 409 `TIME_CONFLICT`, 이미 신청한 회차면 `GROUP_ALREADY_APPLIED`이고 대기와 제안은 그대로 둔다.
- 공개 일행 신청(`POST /application/pre-standard/{expoId}`)은 대표 번호당 한 번이다(`samePhoneInUse` 그대로). User는 이미 있는 번호에 대해 일행 전체 ID를 돌려주므로 두 번째 회차의 제출 구성과 ID를 짝지을 수 없다. 이미 신청한 일행의 다른 회차는 참가자 ID로 고르는 "다른 회차 추가"(#199)로 받는다. 대표 불참 회차와 "학생 아닌 참석자 1명 이상" 규칙(Q1)도 #199에서 적용한다.
- 취소는 회차별이다. 대표자가 자기 회차를 취소해도 동행자 신청은 남는다(`ACTIVE_COMPANIONS` 사유와 409 제거). 그 회차의 대표자 신청에 붙이려던 대기 중 동행자 추가만 함께 철회한다. 활성 프로그램 차단은 #200 전까지 그대로다.
- 동행자 추가 진행 중 유니크(`uq_preregister_companion_in_progress`)는 대표자 단위로 유지한다. User 일행 5명 한도가 박람회 단위라 추가를 한 번에 하나씩 처리한다.

## 다른 회차 추가 (#199, V34)

`POST /application/expos/{expoId}/my-applications/preregister-sessions` 본문 `{"sessionId":S,"participantIds":[...]}`. 참가자 세션 쿠키와 CSRF 토큰이 필요하고 `Idempotency-Key`는 선택이다.

- 참석자는 세션 번호의 User 일행(`/standard-participants/managed`)에서 1~5명을 고른다. 일행에 없는 ID는 400이다. 대표자는 빠져도 되지만, 참석자가 모두 학생(유치원·초·중·고)이면 400이다(Q1).
- 대표자가 처음 낸 일행 신청의 `user_request`를 복사한다. 동행자 답변은 이번 참석자만 남기며, 동행자 추가(#169)로 더한 사람의 답변도 찾아 쓴다. 신청 뒤 사전등록 폼 ID가 바뀌었으면 409 `{"status":409,"code":"FORM_CHANGED"}`로 처음 신청부터 다시 하게 한다. 문항 수정은 보지 않는다.
- 박람회 잠금 안에서 이미 신청한 회차(참석자의 활성 신청, 같은 번호로 처리 중이거나 대기 중인 예약)와 확정 회차와의 시간 겹침을 409로 거절한다. 마감·시작된 회차, 사전등록 기간이 아닌 경우도 409다. 정원·대기·대상별 정원 판정은 일반 신청과 같다.
- User에 저장하지 않는다. 예약(`session_addition`)의 `result`에 대표자와 참석자를 미리 담아 기존 복구 경로가 User 호출 없이 확정한다. QR·문자·인원 집계는 그대로다. 좌석 구분은 완료 때 참석자의 User 직업으로 정한다.
- 대기했다 승급된 추가가 그사이 같은 회차를 신청했거나 시간이 겹치게 됐으면 User에 저장한 것이 없으므로 검토로 남기지 않고 좌석을 돌려준다(409).
- 응답은 201 `CONFIRMED`(확정 참석자 ID), 202 `WAITING`(position) 또는 `PROCESSING`이다.
- Gateway·Config 익명 경로에 이 POST 경로를 등록해야 한다.

## 내 대기 조회·철회

- `GET /application/expos/{expoId}/my-applications/preregister-waitlists`: 회차별 대기 목록(최근 순, 없으면 `[]`). 항목 형식은 기존 단건과 같다.
- 기존 `GET .../preregister-waitlist`, `POST .../preregister-waitlist/cancellation`은 그대로이며 선택 쿼리 `sessionId`를 받는다. 없으면 가장 최근 1건이다.
- 참여 제안 목록의 사전등록 제안은 대기 예약마다 최신 1건이다.

## Attendance 회차 통지 계약 (Expo-Attendance-Server#160)

| 동작 | 요청 | 의미 |
|---|---|---|
| 확정 | `PUT /internal/expos/{expoId}/participants/{participantId}/preregister-session` 본문 `{"sessionId":S,"revision":R}` | `(expo, participant, S)` 배정을 추가·갱신한다. 다른 회차 배정은 지우지 않는다. |
| 회차 취소 | `DELETE .../preregister-session?revision=R&sessionId=S` | 회차 S 배정만 해제한다. |
| 전체 해제 | `DELETE .../preregister-session?revision=R` | 그 참가자의 모든 회차 배정을 해제한다. 박람회 삭제(purge)와 V33 이전에 쌓인 취소 기록이 쓴다. |

- `revision`은 Application outbox ID × 2(+1은 DELETE)로, 같은 참가자 안에서 커밋 순서대로 증가한다. 참가자별 발송 순서는 기존과 같다.
- 취소 보호(`cancellation-guard`)의 `committed` revision은 회차 취소 DELETE의 revision과 같다.

## 배포

- Attendance #160(회차별 배정, `sessionId` 쿼리 DELETE)이 먼저 배포돼야 한다. 이전 Attendance는 PUT을 사람 단위로 덮어써 두 번째 회차가 첫 회차를 지운다.
- 새 GET 경로 `/my-applications/preregister-waitlists`는 Gateway·Config 익명 경로에 등록돼 있는지 확인한다.
