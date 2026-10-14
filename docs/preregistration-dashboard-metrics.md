# 사전등록 직업별 집계와 신청화면 PV·UV (#108)

## 범위 결정

- 기존 계획(`expo-v2-integrated-change-plan.md:317,377`)은 PV·UV를 제외했다. 2026-10-14 작업 창에서 사용자가 "보류" 선택지 대신 이 서버 수집, 익명 UUID, 행사별 KST 자정을 직접 선택했고, 최신 직접 승인이 우선한다.
- 직업군 집계는 이슈 #108이 요청한 최소 범위로, 행사 단위 직업별 값만 제공한다. 회차별 분할과 직업군 묶음은 만들지 않는다.

## 직업별 사전등록 집계

`GET /application/admin/expos/{expoId}/preregistrations/summary`의 `occupations[]`는 9개 직업과 `occupation: null`(미상)을 항상 포함한다.

| 값 | 원천 | 미상(null) |
|---|---|---|
| `confirmedParticipants` | 원장 확정 참가자 ID로 조회한 User 참가자 직업 | User 직업이 비어 있음 |
| `waitingParticipants` | User에 저장하지 않은 대기 예약 스냅샷 `user_request` | 대표자 직업 문항이 없거나 비어 있음, 또는 알 수 없는 값 |

- 대기 동행자는 신청 시 인원 산정과 같은 (이름 trim, 직업, 학교) 기준으로 중복을 제거한다. 직업별 합계는 `confirmedUniqueParticipants`, `waitingParticipants`와 같다.
- User 확정 전 예약(RESERVED)은 직업별 집계에 넣지 않고 `pendingApplicants`에만 남는다.
- User가 확정 ID를 누락하거나 응답 계약을 어기면 미상으로 채우지 않고 요약 전체가 502/503이다.
- "교원·예비교사"는 클라이언트가 합산한다. SCHOOL_STAFF 포함 여부는 미정이다.

## 신청화면 PV·UV

- 기록: `POST /application/expos/{expoId}/page-views`, 본문 `{"visitorId":"<UUID>"}`, 204. 로그인과 CSRF가 없는 공개 경로다.
  - 클라이언트는 localStorage에 무작위 UUID를 한 번 만들어 재사용하고, `/application/[id]`, `/application/forms/[id]`, `/application/forms/[id]/pre-register` 진입마다 한 번 호출한다.
  - 진입과 새로고침마다 PV가 1 늘고, UV는 같은 `visitorId`를 Asia/Seoul 날짜별 한 번 센다.
  - 서버는 `SHA-256(expoId:date:visitorId)`만 저장한다. IP와 쿠키는 쓰지 않는다.
  - 행사 존재는 확인하지 않는다. Gateway의 IP별 기본 제한이 무의미한 행을 제한한다.
- 조회: `GET /application/admin/expos/{expoId}/page-views`는 Expo `APPLICATION_READ` 검증 뒤 `today{date,pageViews,uniqueVisitors}`, `totalPageViews`, 날짜 오름차순 `daily[]`를 `no-store`로 반환한다.

## 배포 조건

1. #107의 V18 뒤에 V19를 배포한다. #108이 먼저 병합되면 마이그레이션 번호를 다시 정한다.
2. Config의 dev, local, prod Gateway 익명 경로에 `POST /application/expos/{expoId}/page-views`를 추가한다.
3. 클라이언트가 기록 호출, `page-views` 조회, `occupations` 표시를 구현해야 값이 화면에 나타난다.

## 권장안과 운영 확인 항목 (2026-10-14 피드백, 미확정)

아래는 전달받은 권장안이다. 사용자의 최종 정책 승인이나 법적 판단이 아니다. 서버 구현은 2항의 hash 정리뿐이고, 배포는 하지 않았다.

### 1. 방문 식별자 안내

- "익명이라 고지 불필요"로 단정하지 않는다. 운영자에게 아래 사실을 전달하고, 개인정보처리방침이나 이용 안내에 반영할지 확인받는다. 법적 분류와 동의 필요 여부는 여기서 정하지 않는다.
  - 클라이언트: 무작위 `visitorId`를 localStorage에 보관한다. 수명은 2항의 visitorId 수명 제안을 따른다.
  - 서버: 행사·KST 일자로 salt한 SHA-256 해시만 저장한다. 목적은 하루 UV 중복 제거이고, 보관과 삭제는 2항을 따른다.
- IP, UA, 전화번호 등 수집 항목을 늘리지 않는다.

### 2. 보관과 삭제

- 권장: 방문자별 일별 해시(`tb_application_page_visitor`)는 해당 KST 일자가 끝난 뒤 짧은 유예(제안값 48시간, 미확정)를 두고 삭제한다. 행사별 일별 합계(`tb_application_page_view_daily`)는 개인별 키가 없으므로 유지한다.
- 현재 구현의 영향:
  - UV는 hash 행을 세지 않는다. 기록할 때 새 hash가 들어간 경우에만 `unique_visitors` 카운터를 올린다. 그래서 hash를 지워도 과거 PV·UV 수치는 바뀌지 않는다.
  - 집계 날짜는 서버가 수신 시각으로 정한다. 지난 날짜로 기록되는 재전송은 없고, 자정을 넘긴 재전송은 새 날짜의 방문으로 센다. 따라서 삭제한 날짜의 hash가 다시 필요해지지 않는다.
  - 정리 작업이 실패하면 hash가 더 오래 남을 뿐이고, 다음 실행에서 다시 지우면 된다. 수치에는 영향이 없다.
- 구현(2026-10-14 사용자 진행 지시): 스키마 변경 없이 `ApplicationPageVisitorPurgeScheduler`가 1시간마다 KST 일자 종료 뒤 유예가 지난 hash 행을 지운다. 유예는 `APPLICATION_PAGE_VIEW_VISITOR_HASH_RETENTION_HOURS`(기본 48)로 조정한다. 48시간은 여전히 제안값이며 운영 확정이 필요하다. 일별 합계 행은 삭제하지 않는다.
- visitorId 수명 제안(Client 계약): 서버가 일자별로 salt하므로 UV에는 하루 단위 식별만 필요하다. 클라이언트는 `{KST 일자, UUID}`를 저장하고 일자가 바뀌면 새 UUID를 만든다. 장기 고정 ID는 두지 않는다.

### 3. 봇과 관리자

- 관리자 미리보기·관리 화면에서는 Client가 기록 API를 호출하지 않는다.
- 서버는 요청에 담긴 관리자 제외 플래그를 신뢰하지 않으며, 그런 필드를 받지 않는다.
- 공개 신청 화면의 관리자 분류나 별도 봇 판별 시스템은 추가하지 않는다.
- 이 통계는 봇이 포함될 수 있는 참고용 방문 통계로 표시한다. UV를 실제 사람 수로 표현하지 않는다(예: "고유 방문(참고)").
- 운영 확인: Gateway 기본 IP별 제한(조사 시점 60초당 500회)을 이 경로에 그대로 쓸지, 별도 이름 있는 제한을 둘지 결정한다.

### 4. 직업군 표시 규칙

- 서버 응답은 그대로 둔다. 9개 직업별 값과 `occupation: null`을 계속 반환하고 직업군 묶음은 만들지 않는다.
- 권장 표시 매핑(Client):
  - 교원·예비교사 = `TEACHER` + `PRE_SERVICE_TEACHER`. `SCHOOL_STAFF`는 넣지 않는다.
  - 일반(묶음) = 나머지 분류된 직업 7개(`KINDERGARTEN_STUDENT`, `ELEMENTARY_STUDENT`, `MIDDLE_SCHOOL_STUDENT`, `HIGH_SCHOOL_STUDENT`, `SCHOOL_STAFF`, `PARENT`, `GENERAL`)의 합
  - 미분류 = `null`. 일반에 합치지 않고 따로 표시한다.
- 직업 코드 `GENERAL`(일반인)과 일반 묶음을 화면 문구에서 구분한다(예: "일반인" vs "교원 외").
- 이 규칙은 통계 표시용이다. 프로그램 신청 자격(`policy.allowedOccupations`)이나 Expo-Expo-Server #112 회차별 채널 정원·자격 규칙을 바꾸지 않는다.
- 같은 매핑을 공유해야 할 소비자:
  - Client 사전등록 대시보드 배너
  - #107 관리 원장의 직업 필터·표시(`AdminOccupation`)
  - #112 채널 화면에서 직업군을 통계로 보여 줄 경우
  - Client가 `occupationData.ts`, `ledger.ts`의 `OCCUPATION_LABEL` 옆에 이 묶음을 한 곳에 정의하도록 권장한다.
