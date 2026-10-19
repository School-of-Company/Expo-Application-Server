# 사전등록 회차 취소 시 프로그램 함께 취소 (#200)

설계: `expo-multi-session-design-2026-10-19.md` 2장(회차 취소). 결정일 2026-10-19.

## 대상

사전등록 신청 하나를 취소하면, 그 참가자의 CONFIRMED·WAITING 프로그램 신청 중 **취소 뒤 남는 CONFIRMED 사전등록 회차 어디와도 시간이 겹치지 않는 것**을 함께 취소한다.

- 겹침: 일부 겹침, 반열린 구간 `[startedAt, endedAt)`. 입장 허용 `leadMinutes`는 넣지 않는다.
- 회차 시간은 `tb_preregister_session_state.definition`, 프로그램 시간은 `COALESCE(pending_definition, definition, definition_snapshot)`로 로컬에서 읽는다(`ProgramSessionApplicationRepository.preregisterCascade`).
- 일행 일괄 취소에서는 같은 요청에서 먼저 취소된 회차도 남은 회차에서 빠진다.

## 처리

- 사전등록 취소와 같은 트랜잭션에서 대상마다 `CancelProgramSessionService`를 부른다. 확정 행 삭제, 연수 trainee version, 프로그램 대기 승급, 이력의 실행자(USER/ADMIN/UNKNOWN)가 단독 프로그램 취소와 같다.
- Attendance는 사전등록 취소 permit 하나(참가자 단위 guard)로 함께 보호되고, 프로그램에 대한 별도 통지는 없다.
- 하나라도 거절되면 전체를 롤백하고 409다. 프로그램 취소 기한이 지난 경우, 학생·보호자 묶음 신청이 그 예다.
- 세션 모델 이전의 레거시 직접 행(`tb_standard_program_application`·`tb_training_program_application` 중 확정 세션 신청이 없는 행)은 시간 정보가 없으므로 지금처럼 409로 막는다. 내 신청 목록의 `ACTIVE_PROGRAMS`도 이 경우만 뜻한다.

## API

- `GET /application/expos/{expoId}/my-applications/PREREGISTER/{applicationId}/cancellation-preview`: 참가자 세션 쿠키 인증. `{ "programs": [{ applicationId, programType, programId, status, title, startedAt, endedAt }] }`. 이미 취소된 신청이면 빈 목록이다.
- `POST /application/expos/{expoId}/my-applications/{type}/{applicationId}/cancellation`: 선택 본문 `{ "programApplicationIds": [UUID] }`. 보내면 서버가 계산한 대상과 집합이 같아야 하며, 다르면 409다. 생략하면 확인 없이 서버 계산대로 취소한다(관리자·내부 경로 포함).
- 관리자 미리보기는 만들지 않았다.
