# 신청 이력이 있는 회차 변경(#268, B안)

신청 이력이 있는 회차는 시간 항목과 구성만 잠그고, 그 밖의 값은 하한 검사를 통과하면 변경을 승인한다.

| 대상 | 이력 기준 | 잠금 | 허용(하한 검사) |
| --- | --- | --- | --- |
| 사전등록 회차 | 취소 포함 신청 행 또는 REJECTED가 아닌 예약 | `startedAt`, `endedAt`(같은 KST 날짜 안의 단축 제외), `cancelEndedAt`, 삭제, 대기 일행이 있을 때 구분 정원 | `capacity`·구분 정원(사용 좌석 이상), 채택된 `waitingCapacity`(대기 인원 이상), `title`, `place`, `closed`, 종료 단축(#324) |
| 프로그램 회차 | 활성(취소 아님) 또는 확정 인원 | `startedAt`, `endedAt`, 삭제, `groupId`, 정책 제거, 채널 구성 | 정원·대기 정원·채널·경로별 정원(확정·제안·대기 이상), 신청 기간, 승급 설정, `promotionEndedAt`, 허용 직업, `guardianPolicy` |

- 시각은 같은 시각의 다른 표기(#251)를 같은 값으로 본다.
- 사전등록 종료 단축(#324)은 `startedAt < 새 endedAt < 기존 endedAt`이고 두 종료의 KST 날짜가 같을 때만 받으며, 함께 바뀐 값과 같이 하한 검사를 거친다. 연장·날짜 이동은 `TIME_LOCKED_BY_HISTORY`다. 기존 신청·예약·QR은 다시 쓰지 않고, 기존 프로그램 신청의 회차 겹침(#197)도 다시 판정하지 않는다.
- 채택하지 않은(null) 사전등록 대기 정원은 비교하지 않고 그대로 미적용이다. 채택은 기존 `waiting-capacity` PUT으로 한다.
- 대기 정원을 줄여도 기존 대기 순번은 바뀌지 않는다.
- 사전등록 참여 제안 문구(`CheckPromotionOfferServiceImpl`)는 저장된 현재 정의를 읽으므로 변경이 반영된 뒤에는 새 `title`·`place`가 나간다. QR과 확정 문자는 User·Attendance가 만들므로 이 저장소에서 확인하지 않았다. 이미 User에 저장된 신청은 다시 쓰지 않는다.

## 보호자 정책

이력이 있어도 `guardianPolicy`를 켜거나 바꿀 수 있다. 정책은 그 뒤 들어온 신청만 검사한다.

- 적용 시점은 신청 행의 `definition_snapshot`(신청 당시 revision과 정책)으로 판정한다. 별도 컬럼은 두지 않는다.
- 단건 대기는 원래 승급 때 보호자 검증을 하지 않는다. 학생·보호자 묶음은 신청 당시 정책이 현재 정책과 같을 때만 승급 전에 다시 검증한다. 다르면 정책 적용 이전 신청이므로 재검증 없이 승급한다.
- 기존 신청은 재분류하지 않는다.

## 409 본문

사유가 정해진 거절은 아래 본문을 돌려준다. 버전 불일치, 같은 키 다른 내용 같은 그 밖의 409는 기존 본문 그대로이며, Expo는 코드가 없으면 기존 문구로 대체한다(Expo-Expo-Server#188).

```json
{"status":409,"code":"TIME_LOCKED_BY_HISTORY","message":"신청 이력이 있는 회차는 시간을 바꿀 수 없습니다."}
```

| code | 의미 |
| --- | --- |
| `TIME_LOCKED_BY_HISTORY` | 이력이 있어 시간 항목을 바꿀 수 없음 |
| `STRUCTURE_LOCKED_BY_HISTORY` | 이력이 있어 삭제·그룹·채널·정원 구성을 바꿀 수 없음 |
| `BELOW_CONFIRMED_COUNT` | 정원이 확정·사용·제안 좌석보다 작음 |
| `BELOW_WAITING_COUNT` | 대기 정원이 현재 대기 인원보다 작음 |

적용 경로: `PUT /internal/expos/{expoId}/preregister-sessions/{sessionId}/changes/{nextRevision}`, `PUT /internal/expos/{expoId}/program-changes/{changeId}`와 `/guardian-policy`.

프로그램 시간 잠금은 운영 동작을 바꾼다. 행사 직전 시간 조정이 필요하면 별도 경로가 필요하다.
