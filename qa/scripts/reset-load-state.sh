#!/usr/bin/env bash
# ==============================================================================
# 부하 시나리오 사이에 거래 상태를 비운다.
#
# 시나리오를 이어 돌리면 앞 실행이 남긴 것이 뒤 실행을 바꾼다.
#   - Worker를 끈 시나리오의 PENDING 행이 다음 시나리오에 그대로 남는다
#   - 앞 실행이 점유한 좌석 때문에 뒤 실행의 좌석 충돌이 늘어 처리량이 떨어진다
#
# 지우는 것: 결제, 주문, 예매, 승차권, Outbox, 좌석 점유, 예약, 회원 예약 인덱스
# 남기는 것: 역, 열차, 좌석, 스케줄, 회원, Batch 메타데이터, 열차 정적 캐시
#
# 사용:
#   qa/scripts/reset-load-state.sh
#
# 대상은 compose-test의 로컬 MySQL과 Valkey다. .env의 DB를 건드리지 않는다.
# ==============================================================================
set -euo pipefail

cd "$(dirname "$0")/../.."

MYSQL_CONTAINER=${MYSQL_CONTAINER:-raillo-test-mysql}
REDIS_CONTAINER=${REDIS_CONTAINER:-raillo-backend-v2-redis-1}

log() { echo "[reset $(date +%H:%M:%S)] $*"; }

docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || {
  echo "ERROR: $MYSQL_CONTAINER 가 없습니다. bootstrap-test-env.sh 를 먼저 돌립니다." >&2
  exit 1
}

# ------------------------------------------------------------------------------
# DB
#   FK를 잠시 끄고 지운다. 순서를 손으로 맞추면 테이블이 늘 때마다 깨진다.
# ------------------------------------------------------------------------------
log "거래 테이블 비우기"
docker exec -i "$MYSQL_CONTAINER" mysql -uraillo -praillo-local raillo 2>/dev/null <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM ticket;
DELETE FROM seat_booking;
DELETE FROM booking;
DELETE FROM order_seat_booking;
DELETE FROM order_booking;
DELETE FROM orders;
DELETE FROM payment_attempt;
DELETE FROM payment_outbox;
DELETE FROM payment;
SET FOREIGN_KEY_CHECKS = 1;
SQL

# ------------------------------------------------------------------------------
# Redis
#   열차 정적 캐시(train:*)와 스케줄 캐시({schedule:N}:info, :stops)는 남긴다.
#   다시 채우려면 배치를 돌려야 해서 비싸고, 거래 상태가 아니다.
# ------------------------------------------------------------------------------
log "좌석 점유와 예약 비우기"
deleted=0
for pattern in '{schedule:*}:car:*:seats' '{schedule:*}:reservation:*' '{schedule:*}:*:order' 'member:*:reservations'; do
  keys=$(docker exec "$REDIS_CONTAINER" valkey-cli --scan --pattern "$pattern" 2>/dev/null || true)
  [ -n "$keys" ] || continue
  count=$(printf '%s\n' "$keys" | wc -l | tr -d ' ')
  printf '%s\n' "$keys" | xargs -r docker exec -i "$REDIS_CONTAINER" valkey-cli del >/dev/null 2>&1 || true
  deleted=$((deleted + count))
done
log "Redis 키 $deleted 개 삭제"

# ------------------------------------------------------------------------------
# 확인
# ------------------------------------------------------------------------------
remaining=$(docker exec "$MYSQL_CONTAINER" mysql -uraillo -praillo-local raillo -Nse \
  "SELECT (SELECT COUNT(*) FROM payment_outbox) + (SELECT COUNT(*) FROM booking) + (SELECT COUNT(*) FROM payment)" 2>/dev/null)
occupancy=$(docker exec "$REDIS_CONTAINER" valkey-cli --scan --pattern '{schedule:*}:car:*:seats' 2>/dev/null | wc -l | tr -d ' ')
log "남은 거래 행 $remaining, 좌석 점유 키 $occupancy"

if [ "$remaining" != "0" ] || [ "$occupancy" != "0" ]; then
  echo "ERROR: 비우지 못한 것이 있습니다." >&2
  exit 1
fi
log "완료"
