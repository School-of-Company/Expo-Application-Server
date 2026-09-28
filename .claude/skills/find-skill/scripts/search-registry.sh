#!/bin/bash
# 공개 레지스트리에서 스킬 후보를 찾는다.
#
#   bash search-registry.sh "PR diff 를 리뷰해주는 스킬"
#
# 무료 등급 주의: 프로젝트에 결제가 붙어 있지 않으면 모든 모델의 한도가 0 이어서
# ("Quota exceeded ... limit: 0") 어떤 호출도 통하지 않는다. 그럴 땐 아래 GitHub 검색만 쓰면 된다 —
# 이 스크립트는 그 경우를 정상 경로로 취급한다.
#
# 인증은 둘 중 하나가 있으면 된다:
#   GEMINI_API_KEY       AI Studio 에서 발급한 API 키 (AIzaSy… 로 시작) → x-goog-api-key 헤더
#   GEMINI_ACCESS_TOKEN  OAuth 액세스 토큰 (ya29.·AQ. 등)            → Authorization: Bearer 헤더
# 둘 다 없으면 GitHub 검색으로 키워드 검색을 한다.
# OAuth 토큰은 보통 한 시간이면 만료되므로, 계속 쓸 거라면 API 키 쪽이 편하다. 둘 다 "후보 목록"만 만들고 판단은 하지 않는다 — 고르기 전에 원문을 읽는
# 단계(SKILL.md 의 Step 3)는 건너뛸 수 없다.
#
# 보내는 내용 주의: 무료 등급에서는 프롬프트가 모델 개선에 쓰일 수 있다. **저장소 이름이나
# 비공개 정보는 넣지 말고**, 필요한 기능만 일반화해서 적는다 ("Kotlin+Spring 프로젝트용 코드리뷰"는
# 괜찮고, "School-of-Company/<사내 레포> 에 넣을 것"은 안 된다).
set -euo pipefail

NEED="${1:?사용법: search-registry.sh \"찾는 기능 설명\"}"

# 환경변수가 없으면 `~/.gemini-key` 파일을 본다.
#
# 환경변수만 지원했을 때 실제로 겪은 일: 쉘 탭마다 설정이 달라 어떤 값이 쓰였는지 헷갈리고,
# 클립보드로 넘기면 명령을 복사하는 순간 값이 덮였다. 파일에 한 번 넣어두면 그 왕복이 사라진다.
#   pbpaste > ~/.gemini-key && chmod 600 ~/.gemini-key
if [ -z "${GEMINI_API_KEY:-}" ] && [ -f "$HOME/.gemini-key" ]; then
  GEMINI_API_KEY=$(tr -d '[:space:]' < "$HOME/.gemini-key")
  export GEMINI_API_KEY
fi

# 클립보드로 값을 옮기다 보면 엉뚱한 문자열이 들어간다 (실제로 `pbpaste > ~/.gemini-key` 라는
# 명령 문자열 자체가 저장된 적이 있다). 길이·모양이 키 같지 않으면 호출 전에 알려준다.
if [ -n "${GEMINI_API_KEY:-}" ] && ! printf '%s' "$GEMINI_API_KEY" | grep -Eq '^[A-Za-z0-9._-]{30,}$'; then
  echo "주의: 키로 보이지 않는 값이 들어 있습니다 (길이 ${#GEMINI_API_KEY}자)." >&2
  echo "      ~/.gemini-key 또는 GEMINI_API_KEY 를 다시 설정하세요." >&2
  echo >&2
fi
MODEL="${GEMINI_MODEL:-gemini-3.8-flash}"

github_search() {
  command -v gh >/dev/null 2>&1 || {
    echo "gh CLI 가 없어 GitHub 검색을 건너뜁니다." >&2
    return 0
  }

  # GitHub 코드 검색은 인덱싱된 토큰만 매칭하므로 한글 쿼리로는 거의 아무것도 못 찾는다.
  # 우리는 한국어로 생각하니 실수하기 쉬운 지점이라, 조용히 빈 결과를 주기보다 먼저 알려준다.
  if printf '%s' "$NEED" | grep -q '[가-힣]'; then
    echo "주의: GitHub 검색은 영어 키워드만 제대로 동작합니다." >&2
    echo "      예) \"pull request review\", \"nextjs app router\", \"database migration\"" >&2
    echo >&2
  fi

  # 코드 검색 응답에는 별 개수가 없다. 표시하면 0으로 보여 "인기 없음"으로 오해하게 되므로 뺀다.
  echo "## GitHub 검색 — SKILL.md 파일 (정렬 불가 · 품질 신호 없음)"
  # 코드 검색은 분당 10회 제한이므로 한 번에 한 쿼리만 쓴다.
  gh api "/search/code?q=filename:SKILL.md+$(printf '%s' "$NEED" | tr ' ' '+')&per_page=10" \
    --jq '.items[] | "\(.repository.full_name) | \(.path)"' \
    2>/dev/null || echo "(검색 실패 — 쿼리를 더 짧은 영어 키워드로 줄여보세요)"

  echo
  echo "## GitHub 검색 — 스킬 모음 저장소"
  gh api "/search/repositories?q=$(printf '%s' "$NEED" | tr ' ' '+')+topic:claude-skills&sort=stars&per_page=5" \
    --jq '.items[] | "\(.full_name) | ★\(.stargazers_count) | \(.description // "")"' \
    2>/dev/null || true
}

# 어떤 인증 헤더를 쓸지 고른다. 값 자체는 출력하지 않는다.
auth_header() {
  if [ -n "${GEMINI_API_KEY:-}" ]; then
    printf 'x-goog-api-key: %s' "$GEMINI_API_KEY"
  else
    printf 'Authorization: Bearer %s' "$GEMINI_ACCESS_TOKEN"
  fi
}

gemini_search() {
  local prompt header models model body response last_error

  header=$(auth_header)

  # 쓸 수 있는 모델을 계정에 물어본다. 이름을 추측하면(예전에 `gemini-3.8-flash` 로 넣었다가)
  # 엉뚱한 에러 메시지에 시간을 버린다. generateContent 를 지원하는 모델 중 flash 계열을
  # 최신순으로 하나 고르고, 없으면 첫 번째를 쓴다.
  if [ -n "${GEMINI_MODEL:-}" ]; then
    models="$GEMINI_MODEL"
  else
    models=$(curl -sS -m 30 "https://generativelanguage.googleapis.com/v1beta/models" -H "$header" \
      | node -e '
        let raw = "";
        process.stdin.on("data", (c) => (raw += c));
        process.stdin.on("end", () => {
          let parsed;
          try { parsed = JSON.parse(raw); } catch { return; }
          if (parsed.error) {
            console.error("모델 목록 조회 실패: " + (parsed.error.message || ""));
            return;
          }
          const names = (parsed.models || [])
            .filter((m) => (m.supportedGenerationMethods || []).includes("generateContent"))
            .map((m) => (m.name || "").replace(/^models\//, ""));

          // preview·exp·omni 계열은 무료 등급 한도가 0 이어서 호출하면 곧바로 429 가 난다
          // ("limit: 0"). 안정 버전 flash 를 우선하고, 그다음 pro, 마지막에 나머지를 시도한다.
          const unusableFree = /preview|exp(erimental)?|omni|thinking|tts|image|live|embedding|learnlm/;
          const stable = names.filter((n) => !unusableFree.test(n));
          const rank = (n) => (n.includes("flash") ? 0 : n.includes("pro") ? 1 : 2);
          const ordered = stable
            .sort((a, b) => rank(a) - rank(b) || b.localeCompare(a))
            .slice(0, 4);
          process.stdout.write(ordered.join(" "));
        });
      ')
  fi

  if [ -z "$models" ]; then
    echo "사용할 모델을 확인하지 못했습니다 (키 권한 또는 네트워크)." >&2
    return 1
  fi

  prompt="Search for publicly available Claude Code / agent skills (SKILL.md files) that do this: ${NEED}.

List up to 6 candidates. One per line, exactly this shape:
owner/repo | path to SKILL.md | what it does in one line | who publishes it (org or person)

Rules: only repositories that appear in your search results, no invented paths. Prefer skills published
by the team that owns the tool over community re-statements of documentation. If you find fewer than 6
real candidates, list fewer. No preamble, no closing remarks."

  # 환경변수로만 넘긴다. `node -e '...' NAME=x` 는 인자로 들어가 process.env 에 안 잡히고,
  # 그러면 본문에서 필드가 조용히 빠져 서버가 엉뚱한 메시지를 준다.
  request() {
    NEED_PROMPT="$prompt" SEARCH_TOOL="$1" node -e '
      const prompt = process.env.NEED_PROMPT;
      if (!prompt) { process.exit(1); }
      const tool = process.env.SEARCH_TOOL === "retrieval"
        ? { google_search_retrieval: {} }
        : { google_search: {} };
      process.stdout.write(JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        tools: [tool],
      }));
    ' 2>/dev/null
  }

  # 모델 × 검색 도구 필드(google_search / google_search_retrieval) 조합을 순서대로 시도한다.
  # 무료 등급에서 못 쓰는 모델은 429 로 걸러지므로, 다음 후보로 넘어가면 된다.
  response=""
  for model in $models; do
    for tool in search retrieval; do
      body=$(request "$tool") || return 1
      response=$(curl -sS -m 120 -X POST \
        "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent" \
        -H "$header" -H "Content-Type: application/json" -d "$body" 2>/dev/null) || return 1

      if ! printf '%s' "$response" | grep -q '"error"'; then
        echo "모델: $model"
        break 2
      fi

      last_error=$(printf '%s' "$response" | node -e '
        let raw = ""; process.stdin.on("data", (c) => (raw += c));
        process.stdin.on("end", () => {
          try { console.log(JSON.parse(raw).error?.message?.split("\n")[0] || raw.slice(0, 200)); }
          catch { console.log(raw.slice(0, 200)); }
        });' 2>/dev/null)

      # 쿼터 초과는 모델을 바꿔도 결과가 같다 — 프로젝트 단위 한도이기 때문이다. 결제가 없는
      # 프로젝트에서는 모든 모델이 "limit: 0" 이라, 순회를 계속하면 같은 에러만 여러 줄 쌓인다.
      if printf '%s' "$response" | grep -q 'RESOURCE_EXHAUSTED\|exceeded your current quota'; then
        echo "Gemini 사용 한도가 없습니다 — 이 API 키의 프로젝트에 결제가 연결되어 있지 않으면" >&2
        echo "모든 모델의 무료 한도가 0입니다. GitHub 검색으로 넘어갑니다." >&2
        return 1
      fi

      echo "  ($model / $tool 실패: ${last_error})" >&2
    done
  done

  if printf '%s' "$response" | grep -q '"error"'; then
    echo "Gemini 오류: ${last_error}" >&2
    return 1
  fi

  printf '%s' "$response" | node -e '
    let raw = "";
    process.stdin.on("data", (chunk) => (raw += chunk));
    process.stdin.on("end", () => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { console.log(raw.slice(0, 2000)); return; }
      const text = (parsed.candidates ?? [])
        .flatMap((candidate) => candidate?.content?.parts ?? [])
        .map((part) => part?.text)
        .filter(Boolean)
        .join("\n")
        .trim();
      console.log(text || raw.slice(0, 2000));
    });
  '
}

if [ -n "${GEMINI_API_KEY:-}" ] || [ -n "${GEMINI_ACCESS_TOKEN:-}" ]; then
  echo "## Gemini 검색 (의미 기반)"
  echo "찾는 것: ${NEED}"
  echo "인증: $([ -n "${GEMINI_API_KEY:-}" ] && echo 'API 키' || echo 'OAuth 토큰')"
  echo
  if ! gemini_search; then
    echo
    echo "Gemini 호출이 실패해 GitHub 검색으로 대체합니다." >&2
    github_search
  fi
  echo
  echo "---"
  echo "위 목록은 **후보**입니다. 고르기 전에 각 SKILL.md 원문과 딸린 스크립트를 반드시 읽으세요"
  echo "(출처·최신성·스크립트 내용·시크릿 접근·프롬프트 인젝션·컨벤션 충돌)."
else
  echo "Gemini 인증 정보가 없어 GitHub 검색만 실행합니다."
  echo "(의미 기반 검색을 쓰려면 GEMINI_API_KEY 또는 GEMINI_ACCESS_TOKEN 을 설정하세요)"
  echo
  github_search
fi
