# 관리자 신청 현황 변경 SSE (#162)

관리자 종합 대시보드가 신청·취소·승급으로 바뀐 집계와 원장을 다시 읽도록 알리는 신호다. 이벤트는 화면 갱신 신호일 뿐이며 신청·정원·취소·승급·문자 처리의 실행 조건이 아니다. 매 이벤트마다 대시보드 값을 계산해 보내지 않는다.

## 구독

`GET /application/admin/expos/{expoId}/events` (`Accept: text/event-stream`)

- `Authorization: Bearer <관리자 JWT>`를 Expo `admin-access`로 검증한다. 관리자 원장 조회와 같은 `APPLICATION_READ`(`GLOBAL_ADMIN_APPLICATION_READ_V1`)다. 참가자 세션이나 다른 행사 권한으로는 구독할 수 없다.
- 인증정보를 URL query로 받지 않는다. query가 있으면 `400`이다. 브라우저 `EventSource`는 헤더를 보낼 수 없으므로 Client는 fetch 기반 SSE 또는 BFF가 헤더를 붙여 전달한다.
- 응답 헤더: `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`.
- `401`/`403`/`404`는 Expo 권한 결과, `503`은 연결 수 한도 초과나 권한 공급자 장애다.

## 이벤트

```
id:3f0c...
event:admin-data-changed
data:{"eventId":"3f0c...","expoId":"...","resource":"APPLICATIONS","occurredAt":"2026-10-17T09:00:00Z"}
```

- `data`는 `eventId`, `expoId`, `resource`, `occurredAt`뿐이다. 이름·전화번호·폼 답변·내부 토큰을 담지 않는다. Application은 `APPLICATIONS`만 보낸다(`ATTENDANCE`는 Attendance 서버 몫).
- 받으면 변경된 리소스의 기존 GET(목록·요약·현재 회차 등)을 현재 필터로 다시 조회한다. 숫자를 직접 증감하지 않는다.
- heartbeat는 SSE 주석(`:heartbeat`, 기본 25초)이며 데이터 신호가 아니다. 연결 직후 첫 heartbeat로 응답 헤더를 바로 보낸다.

## 재동기화

- 최초 연결과 재연결 직후에는 기존 GET으로 전체 현재값을 다시 읽는다. 이벤트를 보관·재생하지 않으며 `Last-Event-ID`는 쓰지 않는다.
- 중복 이벤트나 연결 유실이 있어도 재조회만 하므로 값이 영구히 어긋나지 않는다.
- 서버의 DB 신호 연결이 끊겼다 복구되면 그 사이 신호를 잃었을 수 있으므로 모든 구독자에게 `admin-data-changed`를 한 번 보낸다.

## 발행 기준

V28 트리거가 신청 원장의 실제 상태 변화에서 `pg_notify('application_admin_changes', expoId)`를 보낸다. 코드 경로마다 발행을 호출하지 않으므로 신청·현장 접수·보호자 묶음·소유자·관리자·연계 취소·자동 승급·참여 제안·복구 경로가 모두 같은 기준을 따른다.

| 테이블 | 신호 |
| --- | --- |
| `tb_program_session_application` | 추가·삭제, `status` 변경 |
| `tb_preregister_application` | 추가·삭제, `status` 변경 |
| `tb_preregister_reservation` | 추가·삭제, `state`·`withdrawn_at` 변경 (재시도 시각·오류 갱신 제외) |
| `tb_promotion_offer` | 추가, 좌석을 잡는 상태와 끝난 상태 사이의 변경 |

- PostgreSQL NOTIFY는 커밋된 트랜잭션만 전달하고 롤백되면 버린다. 같은 트랜잭션의 같은 행사 신호는 한 번으로 합쳐진다. 상태가 바뀌지 않는 재시도는 신호를 만들지 않는다.
- 각 Application 인스턴스가 한 DB 연결로 같은 채널을 LISTEN 한다. 변경을 처리한 인스턴스와 구독 인스턴스가 달라도 전달된다. 이 연결은 Hikari 풀(`maximum-pool-size` 15)에서 하나를 계속 쓰므로 켜면 요청이 쓸 수 있는 연결이 하나 줄어든다.
- 기존 legacy 표(`tb_standard_program_application`, `tb_training_program_application`)만 바꾸는 내부 API는 행사 ID가 없어 신호를 보내지 않는다. 관리자 대시보드는 신청 원장을 읽는다.

## 연결 관리

공통 `global/sse/SseConnectionRegistry`가 연결 등록·해제, heartbeat, 전송 실패·수명 만료 정리를 맡고 도메인은 신호만 넘긴다.

- 요청 스레드를 점유하지 않는다. 전송은 공용 송신 스레드(기본 2개)에서 한다.
- 연결마다 보내지 않은 데이터 이벤트는 최신 하나만 남긴다. 느린 수신자에게 이벤트가 쌓이지 않으며, 송신 큐는 연결 수 한도를 넘지 않는다.
- 전송 실패, 클라이언트 종료, 수명 만료 때 연결을 정리한다.
- 권한: 연결 수명(기본 30분)이 끝나면 서버가 닫는다. 재연결 때 권한을 다시 검증하므로 권한 회수나 토큰 만료는 최대 연결 수명 안에 반영된다.

| 설정 | 기본값 |
| --- | --- |
| `APPLICATION_ADMIN_EVENTS_ENABLED` | `false` |
| `APPLICATION_ADMIN_EVENTS_MAX_CONNECTIONS` | `500` (인스턴스당) |
| `APPLICATION_ADMIN_EVENTS_MAX_CONNECTIONS_PER_EXPO` | `50` |
| `APPLICATION_ADMIN_EVENTS_CONNECTION_LIFETIME` | `PT30M` |
| `APPLICATION_ADMIN_EVENTS_HEARTBEAT_INTERVAL` | `PT25S` |

꺼져 있으면 구독 경로가 없고(`404`) LISTEN 연결도 만들지 않는다. 트리거는 항상 신호를 보내지만 듣는 연결이 없으면 버려진다.

이 값은 Config Server가 아니라 서버 호스트의 실행 환경(deploy.sh·환경 파일)에서 정해진다. 켜졌는지는 토큰 없이 Application에 직접(Gateway를 거치지 않고) 확인한다. Gateway는 JWT 없는 `/application/admin/**`을 먼저 막으므로 이 판별에 쓸 수 없다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' "http://<application-host>:<port>/application/admin/expos/<expoId>/events"
```

- `404`: 꺼짐(`APPLICATION_ADMIN_EVENTS_ENABLED`가 `true`가 아님).
- `401`: 켜짐. 구독 서비스가 Expo 권한 조회 전에 Authorization 없음을 거절한다.

켜진 상태에서 관리자 JWT로 받은 `404`는 Expo `/internal/expo/{expoId}/admin-access`가 돌려준 `404`를 그대로 전달한 것이다. 관리자 원장 조회도 같은 검증을 쓰므로, 같은 관리자·행사로 원장 GET이 성공했다면 그 경우가 아니다.

## 운영 전 확인

- Gateway가 `/application/admin/**`를 지금처럼 프록시하며 `text/event-stream`을 버퍼링하지 않고 바로 흘려보내는지, 응답 timeout이 연결 수명(30분)보다 길거나 SSE에서 꺼져 있는지 확인한다. Authorization 헤더 전달도 확인한다.
- DB 앞에 트랜잭션 단위 커넥션 풀러(예: PgBouncer transaction mode)가 있으면 LISTEN이 동작하지 않는다.
- Client는 연결·재연결 직후 GET 재조회와 이벤트 수신 시 재조회를 구현한다.
