#!/usr/bin/env bash
# ==============================================================================
# 부하 시나리오 하나를 돌리고 지표를 걷는다.
#
# 설정을 환경변수로 받아 raillo-server를 재기동하고, k6를 돌린 뒤, 실행 전후의
# Prometheus 지표를 저장한다. 시나리오마다 같은 절차를 밟아 비교할 수 있게 한다.
#
# 사용:
#   qa/scripts/run-load-scenario.sh <시나리오 이름> [KEY=VALUE ...]
#
# 예:
#   # Worker 정지 기준선
#   qa/scripts/run-load-scenario.sh baseline \
#     RAILLO_PAYMENT_OUTBOX_ENABLED=false RAILLO_PAYMENT_RECOVERY_ENABLED=false
#
#   # 정상 결제 (기본값)
#   qa/scripts/run-load-scenario.sh normal
#
#   # Outbox와 복구 Worker 동시 실행
#   qa/scripts/run-load-scenario.sh both RAILLO_PAYMENT_RECOVERY_ENABLED=true
#
#   # 배치 크기와 폴링 간격 조합
#   qa/scripts/run-load-scenario.sh batch25-1s \
#     RAILLO_PAYMENT_OUTBOX_BATCHSIZE=25 RAILLO_PAYMENT_OUTBOX_POLLINGINTERVAL=1s
#
# 받는 설정 (compose-test.yaml이 기본값을 가진다):
#   RAILLO_PAYMENT_OUTBOX_ENABLED, RAILLO_PAYMENT_OUTBOX_BATCHSIZE,
#   RAILLO_PAYMENT_OUTBOX_POLLINGINTERVAL, RAILLO_PAYMENT_RECOVERY_ENABLED
#
# 결과는 OUT_DIR(기본 ${TMPDIR}/raillo-load/<시나리오>-<타임스탬프>)에 남는다.
# 저장소에 쓰지 않는다. 보고서에 넣을 값만 골라 옮긴다.
#
# 시작할 때 reset-load-state.sh 로 거래 상태를 비운다. SKIP_RESET=true 로 끌 수 있다.
#
# 전제: qa/scripts/bootstrap-test-env.sh 가 끝나 스택이 떠 있어야 한다.
# ==============================================================================
set -euo pipefail

cd "$(dirname "$0")/../.."

SCENARIO=${1:-}
if [ -z "$SCENARIO" ]; then
  echo "사용: $0 <시나리오 이름> [KEY=VALUE ...]" >&2
  exit 1
fi
shift

APP_METRICS=http://localhost:8091/actuator/prometheus
REDIS_METRICS=http://localhost:9121/metrics
HEALTH=http://localhost:8091/actuator/health
K6_SCRIPT=${K6_SCRIPT:-qa/k6/payment-metrics-test.js}
OUT_DIR=${OUT_DIR:-${TMPDIR:-/tmp}/raillo-load/${SCENARIO}-$(date +%Y%m%d-%H%M%S)}

log() { echo "[$SCENARIO $(date +%H:%M:%S)] $*"; }

for cmd in docker curl k6; do
  command -v "$cmd" >/dev/null || { echo "ERROR: $cmd 가 필요합니다." >&2; exit 1; }
done
[ -f "$K6_SCRIPT" ] || { echo "ERROR: $K6_SCRIPT 가 없습니다." >&2; exit 1; }
[ -f qa/k6/schedule-config.json ] || {
  echo "ERROR: qa/k6/schedule-config.json 이 없습니다. bootstrap-test-env.sh 를 먼저 돌립니다." >&2
  exit 1
}

mkdir -p "$OUT_DIR"

# ------------------------------------------------------------------------------
# 1. 설정을 적용해 재기동
#    @ConditionalOnProperty는 기동 시점에 평가되므로 재기동해야 Worker가 켜지고 꺼진다.
# ------------------------------------------------------------------------------
# 앞 시나리오가 남긴 거래 상태를 비운다. 안 비우면 적체와 좌석 점유가 다음 실행에 섞인다.
if [ "${SKIP_RESET:-false}" != "true" ]; then
  log "0/5 거래 상태 초기화"
  qa/scripts/reset-load-state.sh
fi

log "1/5 설정 적용과 재기동"
: > "$OUT_DIR/settings.txt"
for kv in "$@"; do
  case "$kv" in
    *=*) export "${kv?}"; echo "$kv" >> "$OUT_DIR/settings.txt" ;;
    *) echo "ERROR: KEY=VALUE 형식이 아닙니다: $kv" >&2; exit 1 ;;
  esac
done
docker compose -f compose-test.yaml up -d --force-recreate raillo-server >/dev/null

log "2/5 기동 대기"
for _ in $(seq 1 30); do
  if curl -fs --max-time 3 "$HEALTH" 2>/dev/null | grep -q UP; then break; fi
  sleep 2
done
curl -fs --max-time 3 "$HEALTH" 2>/dev/null | grep -q UP || {
  echo "ERROR: raillo-server 가 기동하지 못했습니다. docker logs raillo-server 를 봅니다." >&2
  exit 1
}

# 실제로 적용된 설정을 남긴다. 이름을 틀리면 조용히 기본값으로 돌기 때문이다.
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' raillo-server \
  | grep '^RAILLO_PAYMENT_' | sort > "$OUT_DIR/applied-env.txt" || true

# 돌고 있는 jar에 측정할 지표가 있는지 본다. compose가 build/libs를 마운트해서
# 다른 브랜치에서 gradle build를 돌리면 조용히 바뀐다.
MISSING=""
PROBE=$(curl -fs --max-time 10 "$APP_METRICS")
for metric in payment_outbox_poll_seconds_count payment_outbox_processed_total \
              payment_outbox_retry_scheduled_total payment_outbox_oldest_pending_seconds; do
  printf '%s' "$PROBE" | grep -q "^$metric" || MISSING="$MISSING $metric"
done
if [ -n "$MISSING" ]; then
  echo "ERROR: 지표가 없습니다:$MISSING" >&2
  echo "       ./gradlew :raillo-api:bootJar 로 현재 브랜치를 빌드한 뒤 다시 돌립니다." >&2
  exit 1
fi

# ------------------------------------------------------------------------------
# 2. 실행 전 지표
# ------------------------------------------------------------------------------
log "3/5 실행 전 지표 저장"
curl -fs --max-time 10 "$APP_METRICS" > "$OUT_DIR/app-before.prom"
curl -fs --max-time 10 "$REDIS_METRICS" > "$OUT_DIR/redis-before.prom"

# ------------------------------------------------------------------------------
# 3. k6
# ------------------------------------------------------------------------------
log "4/5 k6 실행: $K6_SCRIPT"
set +e
k6 run --summary-export "$OUT_DIR/k6-summary.json" "$K6_SCRIPT" > "$OUT_DIR/k6.log" 2>&1
K6_EXIT=$?
set -e
[ "$K6_EXIT" -eq 0 ] || log "    k6 종료 코드 $K6_EXIT. 임계값 미달일 수 있으니 k6.log 를 본다"

# ------------------------------------------------------------------------------
# 4. 실행 후 지표
#    Worker가 적체를 마저 비우는 시간을 준다. 해소 시간 자체를 보려면 DRAIN_SECONDS를 늘린다.
# ------------------------------------------------------------------------------
DRAIN_SECONDS=${DRAIN_SECONDS:-30}
log "5/5 적체 해소 ${DRAIN_SECONDS}초 대기 후 지표 저장"
sleep "$DRAIN_SECONDS"
curl -fs --max-time 10 "$APP_METRICS" > "$OUT_DIR/app-after.prom"
curl -fs --max-time 10 "$REDIS_METRICS" > "$OUT_DIR/redis-after.prom"

log "완료. 결과: $OUT_DIR"
echo
qa/scripts/diff-load-metrics.sh "$OUT_DIR"
