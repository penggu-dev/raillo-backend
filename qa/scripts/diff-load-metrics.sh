#!/usr/bin/env bash
# ==============================================================================
# run-load-scenario.sh 가 남긴 전후 지표의 차이를 표로 낸다.
#
# 사용:
#   qa/scripts/diff-load-metrics.sh <결과 디렉터리>
#
# 카운터는 증가분을, 게이지는 실행 후 값을 보여준다. 어느 쪽으로 볼지는 아래 목록에서 정한다.
# ==============================================================================
set -euo pipefail

DIR=${1:-}
[ -n "$DIR" ] && [ -d "$DIR" ] || { echo "사용: $0 <결과 디렉터리>" >&2; exit 1; }
for f in app-before.prom app-after.prom redis-before.prom redis-after.prom; do
  [ -f "$DIR/$f" ] || { echo "ERROR: $DIR/$f 가 없습니다." >&2; exit 1; }
done

# 지표 하나의 값. 태그가 붙어도 찾는다(모든 지표에 application 태그가 붙는다).
# 같은 이름이 태그별로 여러 줄이면 합친다. 없으면 0.
value_of() {
  awk -v name="$2" '
    $1 == name || index($1, name "{") == 1 { s += $2; found = 1 }
    END { if (found) printf "%.6f", s; else print 0 }
  ' "$1"
}

# cmd 태그가 붙은 redis-exporter 지표의 합.
redis_sum() {
  awk -v prefix="$2" 'index($1, prefix) == 1 { s += $2 } END { printf "%.6f", s + 0 }' "$1"
}

printf '%-34s %14s\n' "지표" "값"
printf '%-34s %14s\n' "----------------------------------" "--------------"

delta() {
  local before after
  before=$(value_of "$DIR/app-before.prom" "$2")
  after=$(value_of "$DIR/app-after.prom" "$2")
  printf '%-34s %14s\n' "$1" "$(awk -v a="$after" -v b="$before" 'BEGIN { printf "%.3f", a - b }')"
}

gauge() {
  printf '%-34s %14s\n' "$1" "$(awk -v v="$(value_of "$DIR/app-after.prom" "$2")" 'BEGIN { printf "%.3f", v }')"
}

echo "[Outbox Worker]"
delta "처리한 행"           payment_outbox_processed_total
delta "재시도로 돌린 행"     payment_outbox_retry_scheduled_total
delta "FAILED 전이"         payment_outbox_failed_total
delta "배치 실행 횟수"       payment_outbox_poll_seconds_count
delta "배치 누적 시간(초)"   payment_outbox_poll_seconds_sum
gauge "남은 PENDING"        payment_outbox_pending
gauge "최고 대기 시간(초)"   payment_outbox_oldest_pending_seconds

PROCESSED=$(awk -v a="$(value_of "$DIR/app-after.prom" payment_outbox_processed_total)" \
                -v b="$(value_of "$DIR/app-before.prom" payment_outbox_processed_total)" \
                'BEGIN { print a - b }')
POLL_SUM=$(awk -v a="$(value_of "$DIR/app-after.prom" payment_outbox_poll_seconds_sum)" \
                -v b="$(value_of "$DIR/app-before.prom" payment_outbox_poll_seconds_sum)" \
                'BEGIN { print a - b }')
POLL_COUNT=$(awk -v a="$(value_of "$DIR/app-after.prom" payment_outbox_poll_seconds_count)" \
                -v b="$(value_of "$DIR/app-before.prom" payment_outbox_poll_seconds_count)" \
                'BEGIN { print a - b }')
awk -v s="$POLL_SUM" -v c="$POLL_COUNT" -v p="$PROCESSED" 'BEGIN {
  if (c > 0) printf "%-34s %14.4f\n", "배치당 평균 시간(초)", s / c
  if (p > 0) printf "%-34s %14.4f\n", "행당 평균 시간(초)", s / p
}'

echo
echo "[결제]"
delta "결제 준비"   payment_prepare_total
delta "결제 승인"   payment_confirm_success_total

echo
echo "[DB 커넥션]"
gauge "사용 중"     hikaricp_connections_active
gauge "대기"        hikaricp_connections_pending
delta "획득 누적 시간(초)" hikaricp_connections_acquire_seconds_sum
delta "획득 횟수"   hikaricp_connections_acquire_seconds_count

echo
echo "[Redis]"
RB=$(redis_sum "$DIR/redis-before.prom" 'redis_commands_total{cmd="evalsha"}')
RA=$(redis_sum "$DIR/redis-after.prom" 'redis_commands_total{cmd="evalsha"}')
DB_=$(redis_sum "$DIR/redis-before.prom" 'redis_commands_duration_seconds_total{cmd="evalsha"}')
DA=$(redis_sum "$DIR/redis-after.prom" 'redis_commands_duration_seconds_total{cmd="evalsha"}')
awk -v rb="$RB" -v ra="$RA" -v db="$DB_" -v da="$DA" 'BEGIN {
  calls = ra - rb; secs = da - db;
  printf "%-34s %14d\n", "Lua(evalsha) 호출", calls
  printf "%-34s %14.4f\n", "Lua 누적 시간(초)", secs
  if (calls > 0) printf "%-34s %14.4f\n", "Lua 호출당 평균(ms)", secs / calls * 1000
}'

echo
echo "[적용된 설정]"
if [ -s "$DIR/applied-env.txt" ]; then sed 's/^/  /' "$DIR/applied-env.txt"; else echo "  (없음)"; fi
