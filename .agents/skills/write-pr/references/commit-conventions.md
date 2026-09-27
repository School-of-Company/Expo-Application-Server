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

`description`

- 한글로 변경 내용을 직접 표현하고 대괄호 접두사를 붙이지 않는다
- `[server]`, `[form]`, `[global]`, `[ci/cd]` 같은 scope 표기는 PR 제목에 사용하지 않는다
- 커밋 메시지의 Conventional Commit scope 규칙은 그대로 유지한다
