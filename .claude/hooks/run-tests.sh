#!/usr/bin/env bash
#
# 전체 빌드와 테스트(./gradlew build)를 돌리고, 통과하면 HEAD의 트리 해시를 기록한다.
# require-tests.sh가 이 기록으로 push와 PR 생성을 허용한다.
#
# 사용: bash .claude/hooks/run-tests.sh
# 커밋하지 않은 변경이 있으면 돌리지 않는다. 테스트한 코드와 push할 커밋이 같아야 한다.
# 테스트는 Docker가 필요하다.
#
# 상세: docs/infra/README.md#에이전트-접근-제한

set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

dirty=$(git status --porcelain --untracked-files=all | grep -v '\.DS_Store$')
if [ -n "$dirty" ]; then
  printf 'run-tests: 커밋하지 않은 변경이 있다. 커밋한 뒤 다시 돌린다.\n%s\n' "$dirty" >&2
  exit 1
fi

tree=$(git rev-parse 'HEAD^{tree}') || exit 1
stamp=$(git rev-parse --git-path raillo-tests-passed)

./gradlew build
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "run-tests: ./gradlew build 실패 (종료 코드 $rc). 기록하지 않는다." >&2
  exit "$rc"
fi

# 빌드 중에 HEAD가 바뀌었으면 기록하지 않는다.
[ "$(git rev-parse 'HEAD^{tree}')" = "$tree" ] || { echo "run-tests: 빌드 중 HEAD가 바뀌었다. 다시 돌린다." >&2; exit 1; }

echo "$tree" > "$stamp"
echo "run-tests: 통과. $(git rev-parse --short HEAD)의 트리를 기록했다."
