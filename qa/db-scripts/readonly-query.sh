#!/usr/bin/env bash
# .env의 DB에 조회 쿼리 하나를 읽기 전용 세션으로 실행한다.
#
# 사용:
#   readonly-query.sh "SELECT ..."
#   readonly-query.sh < query.sql
#
# 허용: SELECT, WITH, TABLE, SHOW, DESCRIBE(DESC), EXPLAIN 문 하나. 그 외는 실행하기 전에 거절한다.
# 세션을 READ ONLY로 열고 SELECT 실행 시간을 10초로 제한한다.
# 대상: .env의 DB_URL(호스트, 포트, DB), DB_USERNAME, DB_PW를 쓴다. ENV_FILE로 다른 파일을 지정할 수 있다.
#       OKE MySQL은 터널을 먼저 연다: ssh -f -N -L 13306:10.0.10.198:30060 raillo-bastion
# 필요: mysql 클라이언트 (brew install mysql-client)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT/.env}

case "${1:-}" in
  -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

if [ $# -gt 0 ]; then SQL=$*; else SQL=$(cat); fi

reject() { echo "거절: $1" >&2; exit 1; }

# mysql 클라이언트 명령(\g, \!, \. 와 줄 맨 앞의 system, source)이 끼어들지 못하게
# 백슬래시를 거절하고 한 줄로 합친다.
case "$SQL" in *\\*) reject "백슬래시는 쓰지 않는다. mysql 클라이언트 명령으로 해석된다" ;; esac
SQL=$(printf '%s' "$SQL" | tr '\n\r\t' '   ')

# 끝의 공백과 세미콜론 하나를 떼고 나면 세미콜론이 없어야 한다. 문 하나만 실행한다.
SQL=$(printf '%s' "$SQL" | sed -e 's/[[:space:]]*$//' -e 's/;$//')
[ -n "${SQL//[[:space:]]/}" ] || reject "쿼리가 비어 있다"
case "$SQL" in *";"*) reject "문 하나만 실행한다. 세미콜론으로 여러 문을 잇지 않는다" ;; esac
case "$SQL" in *"/*!"*) reject "실행 주석(/*! */)은 쓰지 않는다" ;; esac
case "$SQL" in *"--"*|*"#"*) reject "-- 와 # 주석은 쓰지 않는다. 한 줄로 합치면 뒤의 쿼리를 가린다" ;; esac

UPPER=$(printf '%s' "$SQL" | tr '[:lower:]' '[:upper:]' | tr '\n\t' '  ')
FIRST=$(printf '%s' "$UPPER" | sed -e 's/^[[:space:](]*//' -e 's/[^A-Z].*$//')
case "$FIRST" in
  SELECT|WITH|TABLE|SHOW|DESCRIBE|DESC|EXPLAIN) ;;
  *) reject "${FIRST:-?} 문은 실행하지 않는다. SELECT, WITH, TABLE, SHOW, DESCRIBE, EXPLAIN만 허용한다" ;;
esac
W='(^|[^A-Z0-9_])'
E='([^A-Z0-9_]|$)'
[[ "$UPPER" =~ ${W}INTO${E} ]] && reject "INTO(OUTFILE, DUMPFILE, 변수 대입)는 쓰지 않는다"
[[ "$UPPER" =~ ${W}FOR[[:space:]]+(UPDATE|SHARE)${E} ]] && reject "잠금 읽기(FOR UPDATE, FOR SHARE)는 쓰지 않는다"
[[ "$UPPER" =~ ${W}LOCK[[:space:]]+IN${E} ]] && reject "잠금 읽기(LOCK IN SHARE MODE)는 쓰지 않는다"

[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 이 없다" >&2; exit 1; }

env_value() {
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -1 | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}

DB_URL=$(env_value DB_URL)
DB_USER=$(env_value DB_USERNAME)
export MYSQL_PWD
MYSQL_PWD=$(env_value DB_PW)

[[ "$DB_URL" =~ ^jdbc:mysql://([^:/?]+)(:([0-9]+))?/([A-Za-z0-9_]+)(\?.*)?$ ]] || { echo ".env의 DB_URL을 해석할 수 없다: $DB_URL" >&2; exit 1; }
HOST=${BASH_REMATCH[1]}
PORT=${BASH_REMATCH[3]:-3306}
DB=${BASH_REMATCH[4]}

MYSQL=$(command -v mysql || true)
for c in /opt/homebrew/opt/mysql-client/bin/mysql /usr/local/opt/mysql-client/bin/mysql; do
  [ -n "$MYSQL" ] || { [ -x "$c" ] && MYSQL=$c; }
done
[ -x "$MYSQL" ] || { echo "mysql 클라이언트가 없다. brew install mysql-client" >&2; exit 1; }

if ! (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null; then
  echo "$HOST:$PORT 에 연결할 수 없다. 터널을 먼저 연다: ssh -f -N -L 13306:10.0.10.198:30060 raillo-bastion" >&2
  exit 1
fi

echo "대상: $DB_USER@$HOST:$PORT/$DB (읽기 전용)" >&2

# 연결 직후 세션을 읽기 전용으로 바꾼다. 쓰기 문은 서버가 ERROR 1792로 거절한다.
"$MYSQL" --protocol=TCP -h "$HOST" -P "$PORT" -u "$DB_USER" --connect-timeout=5 \
  --init-command="SET SESSION transaction_read_only = ON, SESSION max_execution_time = 10000" \
  "$DB" -e "$SQL"
