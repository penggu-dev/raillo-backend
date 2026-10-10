#!/usr/bin/env bash
# 열차, 회원 데이터와 Batch 메타데이터를 제외한 모든 테이블을 DROP한다.
#
# 사용:
#   drop-tables-except-train-member.sh [--execute [--yes]]
#
#   --execute  실제로 DROP한다. 없으면 지울 테이블만 출력한다
#   --yes      --execute의 DB 이름 확인 입력을 건너뛴다
#
# 대상: .env의 DB_URL(호스트, 포트, DB), DB_USERNAME, DB_PW를 쓴다. ENV_FILE로 다른 파일을 지정할 수 있다.
#       OKE MySQL은 터널을 먼저 연다: ssh -f -N -L 13306:10.0.10.198:30060 raillo-bastion
# 필요: mysql 클라이언트 (brew install mysql-client)
# DROP한 테이블은 dev 프로파일(ddl-auto: update)로 raillo-api를 띄우면 다시 만들어진다. Redis는 건드리지 않는다.
set -euo pipefail

KEEP_TABLES=(
  station station_fare train train_car seat
  train_schedule_template schedule_stop_template train_schedule schedule_stop
  member
)
# BATCH_* 메타데이터는 Hibernate가 만들지 않으므로(initialize-schema: never) 남긴다

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT/.env}

EXECUTE=false
YES=false

while [ $# -gt 0 ]; do
  case "$1" in
    --execute) EXECUTE=true; shift ;;
    --yes) YES=true; shift ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done

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
[ -n "$MYSQL" ] || MYSQL=/opt/homebrew/opt/mysql-client/bin/mysql
[ -x "$MYSQL" ] || { echo "mysql 클라이언트가 없다. brew install mysql-client" >&2; exit 1; }

if ! (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null; then
  echo "$HOST:$PORT 에 연결할 수 없다. 터널을 먼저 연다: ssh -f -N -L 13306:10.0.10.198:30060 raillo-bastion" >&2
  exit 1
fi

run_sql() {
  "$MYSQL" --protocol=TCP -h "$HOST" -P "$PORT" -u "$DB_USER" -N -B "$DB"
}

KEEP_SQL=$(printf "'%s'," "${KEEP_TABLES[@]}")
KEEP_SQL=${KEEP_SQL%,}

ROWS=$(run_sql <<SQL
SELECT IF(LOWER(table_name) IN ($KEEP_SQL) OR UPPER(table_name) LIKE 'BATCH\_%', 'keep', 'drop'), table_name
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
ORDER BY 1, 2;
SQL
)

KEEP=$(awk '$1 == "keep" { print $2 }' <<<"$ROWS")
DROP=$(awk '$1 == "drop" { print $2 }' <<<"$ROWS")

echo "대상: $DB_USER@$HOST:$PORT/$DB"
echo
echo "남길 테이블 ($(grep -c . <<<"$KEEP" || true)):"
grep . <<<"$KEEP" | sed 's/^/  - /' || true
echo
echo "DROP할 테이블 ($(grep -c . <<<"$DROP" || true)):"
grep . <<<"$DROP" | sed 's/^/  - /' || true

if [ -z "$DROP" ]; then
  echo
  echo "DROP할 테이블이 없다."
  exit 0
fi

if ! $EXECUTE; then
  echo
  echo "dry-run이다. 실행하려면 --execute를 붙인다."
  exit 0
fi

if ! $YES; then
  echo
  read -r -p "실행하려면 DB 이름($DB)을 입력: " ANSWER
  [ "$ANSWER" = "$DB" ] || { echo "입력이 일치하지 않아 중단한다."; exit 1; }
fi

DROP_LIST=$(sed 's/.*/`&`/' <<<"$DROP" | paste -sd, -)
run_sql <<SQL
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS $DROP_LIST;
SET FOREIGN_KEY_CHECKS = 1;
SQL

echo
echo "$(grep -c . <<<"$DROP")개 테이블을 DROP했다. dev 프로파일로 raillo-api를 띄우면 다시 만들어진다."
