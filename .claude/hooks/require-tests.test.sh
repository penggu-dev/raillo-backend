#!/usr/bin/env bash
#
# require-tests.sh가 테스트 기록 없는 push와 PR 생성은 막고, 나머지는 통과시키는지 확인한다.
# 훅을 고치면 실행한다: .claude/hooks/require-tests.test.sh

set -uo pipefail

HOOK="$(cd "$(dirname "$0")" && pwd)/require-tests.sh"
REPO=$(mktemp -d)
trap 'rm -rf "$REPO"' EXIT
fail=0

g() { git -C "$REPO" "$@" >/dev/null 2>&1; }

check() {
  local expect=$1 command=$2 code
  jq -n --arg c "$command" '{tool_name: "Bash", tool_input: {command: $c}}' \
    | CLAUDE_PROJECT_DIR="$REPO" bash "$HOOK" >/dev/null 2>&1
  code=$?
  if { [ "$expect" = allow ] && [ "$code" -ne 0 ]; } || { [ "$expect" = block ] && [ "$code" -ne 2 ]; }; then
    printf 'FAIL  %-5s (exit %s)  [%s]  %s\n' "$expect" "$code" "$scenario" "$command"
    fail=1
  fi
}

commit() { echo "$2" > "$REPO/$1"; g add "$1"; g -c user.name=t -c user.email=t@t commit -m "$1"; }
stamp() { git -C "$REPO" rev-parse 'HEAD^{tree}' > "$(git -C "$REPO" rev-parse --absolute-git-dir)/raillo-tests-passed"; }

g init -q
commit README.md base
g update-ref refs/remotes/origin/develop HEAD

scenario='변경 없음'
check allow 'git push -u origin chore/1-x'

scenario='문서만 변경'
commit docs.md doc
check allow 'git push -u origin chore/1-x'
check allow 'gh pr create --base develop --title x --body y'

scenario='코드 변경, 기록 없음'
commit Foo.java code
check block 'git push -u origin chore/1-x'
check block 'git push'
check block 'git -C . push origin HEAD'
check block 'git -c push.default=current push'
check allow 'git log --grep push'
check block 'gh pr create --base develop --title x --body y'
check block 'cd /tmp && git push origin x'
check block './gradlew test; git push'
check allow 'git push origin --delete chore/1-x'
check allow 'git status'
check allow 'git commit -m "push 준비"'
check allow 'gh pr view 1'
check allow 'echo git-push'

scenario='코드 변경, 기록 있음'
stamp
check allow 'git push -u origin chore/1-x'
check allow 'gh pr create --base develop --title x --body y'

scenario='기록 후 새 커밋'
commit Bar.java code
check block 'git push'

scenario='트리가 같은 커밋으로 다시 씀'
stamp
g -c user.name=t -c user.email=t@t commit --amend -m "renamed"
check allow 'git push --force-with-lease'

if [ "$fail" -eq 0 ]; then
  echo "require-tests: 모든 케이스 통과"
fi
exit "$fail"
