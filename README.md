# Application-Service

`APPLICATION_PROGRAM_SESSIONS_ENABLED`는 기본 `false`입니다. `true`이면 기존
내부 apply/replace도 Expo #71의 실제 회차 정의를 조회하고 정책이 설정된 신규
신청을 폼 없는 구형 경로로 추가하지 못하게 합니다. 공급자 계약이 준비된 환경에서만
활성화하세요. 공개 프로그램 신청 API와 운영 프로그램 폼 어댑터는 아직 제공하지 않습니다.

Application #47의 V6~V8을 통합하였습니다. V6~V8 다음 V9/V9_1 순서로 적용해야 합니다.
신규 연수자의 ID는 정원 수용 판정 뒤 User 연결 호출로 처음 얻으므로 현재는
회차 잠금 뒤 연수자 try-lock을 사용합니다. 모든 경로의 trainee-before-group 잠금
순서를 통일하려면 연결을 생성하지 않는 User 조회 또는 좌석 예약 계약이 필요합니다.
공개 접수 활성화 전 Form 조회·Expo 변경 승인·공개 인증 계약과 함께 해결해야 합니다.
