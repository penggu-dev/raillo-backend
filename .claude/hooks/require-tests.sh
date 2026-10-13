#!/usr/bin/env bash
#
# Claude Code PreToolUse 훅. 테스트를 통과하지 않은 커밋을 push하거나 PR로 올리지 못하게 막는다.
#
# git push, gh pr create를 실행하기 전에 HEAD의 트리가 run-tests.sh로 통과한 트리인지 본다.
# origin/develop 대비 바뀐 파일이 문서(*.md)뿐이면 검사하지 않는다.
# 막으면 종료 코드 2로 끝나고 이유를 stderr로 돌려준다. 사람이 터미널에서 직접 치는 명령에는 적용되지 않는다.
#
# 상세: docs/infra/README.md#에이전트-접근-제한
# 검증: .claude/hooks/require-tests.test.sh

set -uo pipefail

block() {
  printf 'require-tests: %s\n상세: docs/infra/README.md#에이전트-접근-제한\n' "$1" >&2
  exit 2
}

# 2가 아닌 실패 종료는 Claude Code가 통과로 처리한다. 검사 중 오류도 막는다.
trap 'rc=$?; [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ] || { echo "require-tests: 검사 중 오류(종료 코드 $rc)로 명령을 막았다." >&2; exit 2; }' EXIT

command -v jq >/dev/null 2>&1 || block "jq가 없어 명령을 검사할 수 없다. 사용자에게 brew install jq 설치를 요청한다."
cmd=$(jq -r '.tool_input.command // empty' 2>/dev/null) || block "훅 입력을 해석할 수 없다."
[ -n "$cmd" ] || exit 0

# 명령 구분자 뒤나 맨 앞의 git push, gh pr create만 본다. 원격 브랜치 삭제는 검사하지 않는다.
sep='(^|[;&|(]|[[:space:]])'
git_opts='([[:space:]]+(-[Cc][[:space:]]+[^[:space:]]+|-[^[:space:]]+))*'
if printf '%s' "$cmd" | grep -Eq "${sep}git${git_opts}[[:space:]]+push([[:space:]]|$)"; then
  printf '%s' "$cmd" | grep -Eq '[[:space:]](--delete|-d)([[:space:]]|$)' && exit 0
elif ! printf '%s' "$cmd" | grep -Eq "${sep}gh[[:space:]]+pr[[:space:]]+create([[:space:]]|$)"; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-.}" || block "프로젝트 디렉터리로 이동할 수 없다."

base=origin/develop
git rev-parse --verify -q "$base" >/dev/null || block "$base를 찾을 수 없다. git fetch origin 후 다시 한다."

changed=$(git diff --name-only "$base"...HEAD) || block "$base 대비 변경 파일을 구할 수 없다."
[ -n "$changed" ] || exit 0
printf '%s\n' "$changed" | grep -vq '\.md$' || exit 0

tree=$(git rev-parse 'HEAD^{tree}')
stamp=$(git rev-parse --git-path raillo-tests-passed)
if [ ! -f "$stamp" ] || [ "$(cat "$stamp")" != "$tree" ]; then
  block "HEAD($(git rev-parse --short HEAD))가 테스트를 통과한 기록이 없다. 먼저 bash .claude/hooks/run-tests.sh로 전체 빌드와 테스트를 돌린다. 실패하면 고치고, Docker가 없어 돌릴 수 없으면 사용자에게 알린다."
fi
exit 0
