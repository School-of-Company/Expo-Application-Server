#!/bin/bash
# 되돌릴 수 없는 명령을 막는다.
#
# 무엇을 막을지 고르는 기준은 "위험해 보이는가"가 아니라 **되돌릴 수 있는가**다. `rm -rf dist` 는
# 다시 빌드하면 되고, `git push --force-with-lease` 는 남의 커밋을 덮지 않는다는 보장이 있다.
# 그런 것까지 막으면 훅을 끄게 되고, 그러면 정작 위험한 것도 같이 열린다.
#
# 그래서 경로·옵션을 보고 판단한다: 시스템과 작업 트리를 날리는 것, 남의 커밋을 덮는 것,
# 원격 리소스를 삭제하는 것만 차단한다.
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name')
[[ "$TOOL_NAME" == "Bash" ]] || exit 0

COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
[[ -n "$COMMAND" ]] || exit 0

block() {
    echo "[Hook] Blocked: $1" >&2
    echo "$2" >&2
    exit 2
}

# ── 시스템 파괴 ──────────────────────────────────────────────────────────────
[[ "$COMMAND" =~ sudo[[:space:]]+rm ]] &&
    block "sudo rm" "관리자 권한 삭제는 훅으로 되돌릴 수 없습니다. 직접 실행하세요."
[[ "$COMMAND" =~ \>[[:space:]]*/dev/(sd[a-z]|hd[a-z]|nvme|vg|mem|kmem|port) ]] &&
    block "device write" "디바이스에 직접 쓰는 명령입니다."
[[ "$COMMAND" =~ (^|[[:space:]])dd[[:space:]]+if= ]] &&
    block "dd" "dd 는 대상을 덮어씁니다."
[[ "$COMMAND" =~ (^|[[:space:]])mkfs ]] &&
    block "mkfs" "파일시스템을 새로 만드는 명령입니다."
echo "$COMMAND" | grep -qE '(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(ba)?sh' &&
    block "pipe to shell" "내려받은 스크립트를 바로 실행합니다. 파일로 받아 읽어본 뒤 실행하세요."

# ── 삭제: 위험한 대상만 ──────────────────────────────────────────────────────
# `rm -rf dist`·`rm -rf node_modules` 는 통과시키고, 홈·루트·저장소 자체를 지우는 것만 막는다.
if echo "$COMMAND" | grep -qE '(^|[[:space:]])rm[[:space:]]+(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)[[:space:]]'; then
    echo "$COMMAND" | grep -qE 'rm[[:space:]]+[^|;]*[[:space:]](/|~|\$HOME|/\*|~/\*|\.|\.\.)([[:space:]]|$)' &&
        block "rm -r on home/root/cwd" "대상이 홈·루트·현재 디렉터리 전체입니다. 지울 경로를 구체적으로 지정하세요."
    echo "$COMMAND" | grep -qE 'rm[[:space:]]+[^|;]*\.git([[:space:]]|/|$)' &&
        block "rm -r on .git" "저장소 메타데이터를 지우면 히스토리가 사라집니다."
fi

# ── git: 복구 불가한 작업 ────────────────────────────────────────────────────
# --force-with-lease 는 원격이 예상 커밋일 때만 덮으므로 허용한다. 맨 --force 는 남의 커밋을 날린다.
if echo "$COMMAND" | grep -qE 'git[[:space:]]+push' && ! echo "$COMMAND" | grep -q 'force-with-lease'; then
    echo "$COMMAND" | grep -qE '(--force([[:space:]]|$)|-f([[:space:]]|$))' &&
        block "git push --force" "다른 사람의 커밋을 덮어쓸 수 있습니다. --force-with-lease 를 쓰거나, 정말 필요하면 직접 실행하세요."
fi
echo "$COMMAND" | grep -qE 'git[[:space:]]+reset[[:space:]]+[^|;]*--hard' &&
    block "git reset --hard" "커밋하지 않은 변경이 사라집니다. git stash 로 치워두고 진행하세요."
echo "$COMMAND" | grep -qE 'git[[:space:]]+clean[[:space:]]+-[a-zA-Z]*f' &&
    block "git clean -f" "추적되지 않은 파일이 영구 삭제됩니다. git clean -n 으로 먼저 확인하세요."

# ── 원격 리소스 삭제 ────────────────────────────────────────────────────────
# 조회·수정(GET/PATCH/PUT)은 통과시키고 DELETE 만 막는다 — 이건 복구 수단이 없다.
echo "$COMMAND" | grep -qE 'gh[[:space:]]+api[^|;]*(-X|--method)[[:space:]]+DELETE' &&
    block "gh api DELETE" "원격 리소스를 삭제합니다. 무엇을 지우는지 확인하고 직접 실행하세요."
echo "$COMMAND" | grep -qE 'gh[[:space:]]+(repo[[:space:]]+delete|release[[:space:]]+delete|secret[[:space:]]+delete)' &&
    block "gh delete" "원격 리소스를 삭제합니다. 직접 실행하세요."

exit 0
