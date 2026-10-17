# 취소된 사전등록자의 프로그램 대기 집계 (#149)

#117 이전에 남은 프로그램 WAITING 행은 승급 조회에서 제외되지만 대기 인원에는 포함됐다.
`waitingCount()`도 `waiting()`과 동일하게 같은 박람회·참가자의 CONFIRMED 사전등록이 있는 행만 센다.
단일·학생/보호자 묶음 신청, 참가자 조회, 정원 변경의 전체·채널별 대기 집계에 함께 적용된다.
공유 `count()`의 CONFIRMED 집계에는 이 조건을 적용하지 않는다.

이슈에서 허용한 집계 필터 수정 방식을 사용하므로 기존 행과 이력을 변경하지 않는다.
마이그레이션이나 일회성 UPDATE는 없으며 데이터 처리 건수는 0건이다.

## dev/prod 확인 SQL

아래 읽기 전용 쿼리를 dev와 prod 각각에서 실행한다. 이 문서 작성 중 운영 DB에는 접속하지 않았다.
PR 작성 시 이 쿼리와 환경별 조회 건수를 함께 기록한다.

```sql
SELECT a.id, a.expo_id, a.program_type, a.program_id,
       a.participant_id, a.channel, a.created_at,
       count(*) OVER () AS stale_waiting_count
FROM tb_program_session_application a
WHERE a.status = 'WAITING'
  AND EXISTS (
      SELECT 1
      FROM tb_preregister_application p
      WHERE p.expo_id = a.expo_id
        AND p.participant_id = a.participant_id
        AND p.status = 'CANCELLED'
  )
ORDER BY a.expo_id, a.program_type, a.program_id, a.created_at, a.id;
```

회귀 테스트: `ProgramSessionApplicationTests.stale waiting*`는 취소된 사전등록자의
WAITING 행만 남은 상태에서 새 신청이 CONFIRMED가 되는지 표준·연수, 채널 유무별로 검증한다.
잔존 WAITING 이력이 보존되고 전체·채널별 대기 집계에서 제외되는지도 확인한다.

## 검증 (2026-10-17)

- 수정 전 재현 4건은 모두 `프로그램 대기 정원이 찼습니다.` 충돌로 실패했다.
- 수정 후 재현 4건과 기존 대기 정원·FIFO 2건, 총 6건이 통과했다.
- 아래 전체 검증을 한 번 실행해 51개 클래스, 589건 모두 통과했다. 실패·오류·스킵은 0건이며 빌드·ktlint·Spotless도 통과했다(10분 43초).
- `git diff --check` 통과. dev/prod 데이터 조회와 외부 서비스 실연동은 실행하지 않았다.

```sh
./gradlew build ktlintCheck spotlessCheck --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

worker 1개를 유지하고 공유 Kotlin daemon 경합을 피하려고 이 실행에만 in-process 컴파일을 사용했다.
