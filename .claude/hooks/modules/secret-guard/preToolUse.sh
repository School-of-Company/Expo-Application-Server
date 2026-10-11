#!/bin/bash
# 시크릿이 밖으로 나가는 두 경로를 막는다.
#
#   1. 파일에 쓰는 경로 — Write/Edit 내용에서 자격증명 패턴을 찾는다
#   2. 읽어서 화면·로그로 꺼내는 경로 — Bash 로 .env·키 파일을 읽는 것을 막는다
#
# 예전에는 1번만 검사했다. 그런데 `cat .env` 한 번이면 값이 대화와 로그에 그대로 남고, 실제로는
# 그게 더 흔한 유출 경로다. 쓰기만 막는 것은 반쪽이었다.
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name')

# ── 1) 파일에 자격증명을 쓰려는 경우 ────────────────────────────────────────
if [[ "$TOOL_NAME" == "Write" ]] || [[ "$TOOL_NAME" == "Edit" ]]; then
    CONTENT=$(echo "$INPUT" | jq -r '.tool_input.content // .tool_input.new_string // empty')
    FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
    # .env 자체는 값을 담는 게 목적인 파일이라 검사 대상이 아니다 (커밋은 .gitignore 가 막는다).
    if [[ "$FILE_PATH" == *.env* ]] || [[ "$(basename "$FILE_PATH")" == ".env" ]]; then
        exit 0
    fi
    PATTERNS=(
        "AKIA[0-9A-Z]{16}"
        "ghp_[A-Za-z0-9]{36}"
        "ghs_[A-Za-z0-9]{36}"
        "github_pat_[A-Za-z0-9_]{82}"
        "sk-[A-Za-z0-9]{48}"
        "sk-proj-[A-Za-z0-9_-]{50,}"
        "-----BEGIN[[:space:]]*(RSA[[:space:]]*|EC[[:space:]]*|OPENSSH[[:space:]]*)?PRIVATE KEY-----"
        "xox[baprs]-[A-Za-z0-9-]+"
    )
    for pattern in "${PATTERNS[@]}"; do
        if printf "%s\n" "$CONTENT" | grep -qE "$pattern"; then
            echo "[Hook] Potential secret detected in $(basename "$FILE_PATH"). Pattern: $pattern" >&2
            echo "Possible secret or credential detected in the file content. Review before writing."
            exit 2
        fi
    done
fi

# ── 2) 시크릿 파일을 읽어 화면으로 꺼내려는 경우 ────────────────────────────
if [[ "$TOOL_NAME" == "Bash" ]]; then
    COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

    # 값이 비어 있는 템플릿은 읽어도 문제가 없다 — 먼저 예외 처리한다.
    if echo "$COMMAND" | grep -qE '\.env\.(example|sample|template|dist)'; then
        exit 0
    fi

    # 내용을 뿜는 명령이 시크릿 파일을 직접 지목하는 경우만 막는다.
    # (`grep -r` 로 트리를 훑는 것까지 막으면 평범한 검색이 불가능해진다)
    READERS='(cat|head|tail|less|more|bat|open|xxd|od|strings|base64|cp|scp)'
    SECRETS='(\.env([.][a-z]+)?|[^[:space:]]*\.(pem|key|p8|p12|jks|keystore)|[^[:space:]]*secrets?/[^[:space:]]*|[^[:space:]]*id_(rsa|ed25519))'

    if echo "$COMMAND" | grep -qE "(^|[|;&[:space:]])${READERS}[[:space:]]+([^|;]*[[:space:]])?${SECRETS}([[:space:]]|$|[|;&])"; then
        echo "[Hook] Blocked: reading a secret file puts its contents in the transcript and logs." >&2
        echo "시크릿 파일을 직접 읽으려 했습니다 — 값이 대화와 로그에 그대로 남습니다." >&2
        echo "값이 아니라 존재·형식만 확인하려던 것이라면 이렇게 하세요:" >&2
        echo "  ls -l <file>                  존재 여부와 권한" >&2
        echo "  wc -c < <file>                길이" >&2
        echo "  grep -o '^[A-Z_]*=' <file>    .env 의 키 이름만 (값 제외)" >&2
        exit 2
    fi
fi

exit 0
