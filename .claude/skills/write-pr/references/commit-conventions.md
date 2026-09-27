# Commit & PR Conventions

## Commit Message Format

`type(scope): description`

- **Type**: `feat` / `fix` / `refactor` / `docs` / `chore` / `test`
- **Scope**: 이 프로젝트의 도메인 이름. 고정 목록이 아니라 **레포가 이미 쓰는 어휘를 그대로** 쓴다

  ```bash
  git log --pretty=%s -200 | grep -oE '^[a-z]+\(([^)]+)\)' | sed -E 's/.*\((.*)\)/\1/' | sort | uniq -c | sort -rn
  ```

  히스토리에 어휘가 없으면 변경 경로에서 도메인 세그먼트를 뽑는다 — 도메인 패키지
  (`.../domain/member/` → `member`), 기능 모듈(`modules/expo/` → `expo`), 모노레포 앱
  (`apps/web/` → `web`). 계층(`service`, `controller`)보다 도메인이 리뷰어에게 더 많은 정보를 준다.
  여러 도메인에 걸치면 `global`, 빌드·CI 전용이면 `ci`
- **Description**: 한글, 명사형 종결, 마침표 없음
  - Good examples: `레포 선택 드롭다운 구현`, `PR 생성 시 base branch 조회 실패 처리`
- Subject line only (no body) — breaking change일 때만 예외적으로 본문에 `BREAKING CHANGE: <설명>` 추가

## PR Title Format

`[scope] description`

- Scope는 커밋의 scope와 동일한 어휘 사용, 소문자 대괄호로 표기: `[server]`, `[catalog]`
- 여러 scope에 걸친 변경은 `[global]` 사용
