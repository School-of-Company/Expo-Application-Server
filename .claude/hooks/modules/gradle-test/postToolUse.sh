#!/bin/bash
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name')

[[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]] || exit 0

FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
CWD=$(echo "$INPUT" | jq -r '.cwd // empty')

[[ "$FILE_PATH" == *.kt || "$FILE_PATH" == *.java ]] || exit 0
[[ "$FILE_PATH" == */test/* ]] && exit 0
FILE_NAME=$(basename "$FILE_PATH")

if [[ "$FILE_PATH" == /* ]]; then
    FILE_ABS="$FILE_PATH"
elif [[ -n "$CWD" ]]; then
    FILE_ABS="$CWD/$FILE_PATH"
else
    exit 0
fi

PROJECT_ROOT=$(git -C "$(dirname "$FILE_ABS")" rev-parse --show-toplevel 2>/dev/null)
[[ -z "$PROJECT_ROOT" ]] && PROJECT_ROOT="$CWD"
[[ -z "$PROJECT_ROOT" ]] && exit 0

MODULE_DIR="${FILE_ABS%%/src/*}"
[[ "$MODULE_DIR" == "$FILE_ABS" ]] && exit 0
[[ -d "$MODULE_DIR/src/test" ]] || exit 0

if [[ -x "$PROJECT_ROOT/gradlew" ]]; then
    GRADLE_CMD="$PROJECT_ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
else
    echo "[Hook] gradlew/gradle 를 찾지 못해 테스트를 건너뜁니다." >&2
    exit 0
fi

REL="${MODULE_DIR#$PROJECT_ROOT}"
REL="${REL#/}"
if [[ -z "$REL" ]]; then
    TEST_TASK=":test"
else
    TEST_TASK=":${REL//\//:}:test"
fi

# 대응하는 테스트 클래스가 실제로 있을 때만 돌린다.
#
# 예전에는 `*ServiceImpl` 파일만 대상으로 삼았는데, 그 명명 관례가 없는 프로젝트에서는 아무 일도
# 일어나지 않았다(조용한 무동작). 파일명 관례 대신 "짝이 되는 테스트가 존재하는가"로 판단하면
# 관례와 무관하게 동작하고, 테스트가 없는 파일에서 무의미한 전체 빌드도 피할 수 있다.
BASE="${FILE_NAME%.*}"
TEST_CLASS=""
for candidate in "${BASE%Impl}Test" "${BASE}Test" "${BASE%Impl}Tests" "${BASE}Spec"; do
    if compgen -G "$MODULE_DIR/src/test/**/$candidate.*" > /dev/null 2>&1 \
       || find "$MODULE_DIR/src/test" -name "$candidate.*" -print -quit 2>/dev/null | grep -q .; then
        TEST_CLASS="$candidate"
        break
    fi
done
[[ -z "$TEST_CLASS" ]] && exit 0

echo "[Hook] Running $TEST_TASK --tests $TEST_CLASS ..." >&2
TEST_OUTPUT=$(cd "$PROJECT_ROOT" && "$GRADLE_CMD" "$TEST_TASK" --tests "$TEST_CLASS" 2>&1)
TEST_EXIT=$?
TAIL=$(echo "$TEST_OUTPUT" | tail -5)
if [[ $TEST_EXIT -ne 0 ]]; then
    echo "[Hook] Test FAILED ($TEST_TASK). Last 5 lines:"
    echo "$TAIL"
    echo "Tests failed after editing $FILE_NAME. Consider running test-fixer agent."
else
    echo "[Hook] Tests passed ($TEST_TASK). Last 5 lines:"
    echo "$TAIL"
fi
exit 0