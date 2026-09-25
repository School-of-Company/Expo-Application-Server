# 리포트 데이터 계약

리포트는 요청 시 다른 서비스의 데이터를 조합해 Excel을 만든다. 자체 저장 상태가 없으므로 리포트 테이블과 Repository는 만들지 않는다. 아래 경로와 파일 형식은 기존 모놀리스의 `excel` 기능을 옮길 때 보존할 참고 계약이며, 서비스 간 신규 API 경로와 응답 DTO는 별도로 정한다.

| 기존 다운로드 | 필요한 데이터 | 출력 |
| --- | --- | --- |
| `GET /excel/{expo_id}` | Expo 존재 여부; User의 해당 박람회 연수자 ID·이름·연수원 ID·전화번호·신청 방식·신청 답변; Application의 연수자–프로그램 연결; Expo의 프로그램 ID·제목·분류 | `사전 교원연수자 정보` 시트. 고정 열 뒤에 연수자 답변 키, 공통 강연·선택 강연 열. `교원연수자_정보_{timestamp}.xlsx` |
| `GET /excel/standard/{expo_id}` | Expo 존재 여부; User의 일반 참가자 ID·이름·전화번호·개인정보 동의·신청 방식·신청 답변; Form/Survey의 참가자별 설문 답변 | `박람회 참가자 정보` 시트. 고정 열 뒤에 신청·설문 답변 키. `Participant_Information.xlsx` |
| `GET /excel/program/{expo_id}?programId={id}` | Expo에 속한 일반 프로그램 확인; Application의 참가자–프로그램 연결; User의 참가자 이름·전화번호·개인정보 동의 | `프로그램 참가자 정보` 시트. 순위·이름·전화번호·동의 여부·학번·서명·비고. 마지막 세 열은 빈 입력란. `Program_Participant_Information.xlsx` |
| `GET /excel/trainee/{trainee_id}` | User의 연수자 이름·연수원 ID·신청 답변·박람회 ID; Expo의 박람회 제목과 프로그램 제목·시작 시각; Application의 연수자–프로그램 연결; 실제 출석 날짜가 필요하면 Attendance의 날짜 | `출석부` 시트. 신청한 프로그램의 날짜·제목·시각으로 인쇄용 출석부 구성. `Trainee_Attendance_{name}.xlsx` |

사람은 User, 프로그램은 Expo, 프로그램 신청 연결은 Application, 실제 QR 출석은 Attendance가 소유한다. 서비스 간 조회에서는 각 ID를 유지해야 하며, 목록이 여러 페이지면 누락·중복 없이 모두 조합해야 한다. 조회 시점 일관성, 서비스 장애 처리, 개인정보 접근 권한, Excel 셀의 수식 주입 방지는 API 이전 시 정한다. 기존 연수자 출석부는 프로그램 출석 상태·입퇴장 시각을 출력하지 않는다.

근거: 기준 모놀리스의 `domain/excel/presentation/ExcelController.java` 및 `domain/excel/service/impl/{TraineeInfoToExcelServiceImpl,StandardParticipantInfoToExcelServiceImpl,ProgramParticipantInfoToExcelServiceImpl,TraineeAttendanceToExcelServiceImpl}.java`.
