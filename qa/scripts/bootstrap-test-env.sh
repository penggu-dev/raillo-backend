#!/usr/bin/env bash
# ==============================================================================
# 로컬 관측성 테스트 환경 부트스트랩 스크립트
#
# compose-test.yaml 기반 완결 스택을 처음부터 세워서 k6 부하 테스트 실행 직전
# 상태까지 만든다. macOS + OrbStack/Docker Desktop 전제로 검증됐다.
#
# 단계:
#   1. .env 존재 확인 (JWT_KEY 등 시크릿이 있어야 앱이 부팅됨)
#   2. jar 빌드 (raillo-api, raillo-batch)
#   3. docker compose -f compose-test.yaml up (MySQL, Redis, WireMock, Prometheus, Grafana, raillo-server)
#   4. MySQL healthy 대기
#   5. raillo-server /actuator/health UP 대기
#   6. batch trainInitialize 실행 (역·열차·좌석·스케줄 적재)
#   7. 회원 100명 생성 (generate_members.py)
#   8. k6용 schedule-config.json 생성 (generate_k6_schedule_config.py)
#
# 이후 실행:
#   K6_WEB_DASHBOARD=true k6 run qa/k6/payment-metrics-test.js
#
# 재실행 시:
#   두 번째 이후 실행에서 이미 만들어진 데이터가 있어도 안전하게 동작하도록 각 단계는 idempotent 하게 처리한다.
#   완전히 초기화하려면 먼저 `docker compose -f compose-test.yaml down -v` 로 볼륨까지 삭제한다.
# ==============================================================================
set -euo pipefail

cd "$(dirname "$0")/../.."
PROJECT_ROOT=$(pwd)

log() { echo "[bootstrap $(date +%H:%M:%S)] $*"; }

# ------------------------------------------------------------------------------
# 1. 사전 조건
# ------------------------------------------------------------------------------
if [ ! -f "$PROJECT_ROOT/.env" ]; then
  echo "ERROR: $PROJECT_ROOT/.env 가 없습니다. 팀에서 시크릿(.env) 파일을 받아 프로젝트 루트에 두세요." >&2
  exit 1
fi

for var in JWT_KEY MAIL_USERNAME MAIL_PASSWORD TOSS_CLIENT_KEY TOSS_SECRET_KEY; do
  if ! grep -q "^${var}=" "$PROJECT_ROOT/.env"; then
    echo "ERROR: .env 에 $var 가 없습니다." >&2
    exit 1
  fi
done

if ! command -v python3 >/dev/null; then
  echo "ERROR: python3 필요. brew install python 등으로 설치해주세요." >&2
  exit 1
fi

# ------------------------------------------------------------------------------
# 2. jar 빌드
# ------------------------------------------------------------------------------
log "1/8 jar 빌드"
./gradlew :raillo-api:bootJar :raillo-batch:bootJar --console=plain

if [ ! -f "$PROJECT_ROOT/raillo-api/build/libs/raillo-api-0.0.1-SNAPSHOT.jar" ]; then
  echo "ERROR: raillo-api jar가 생성되지 않았습니다." >&2
  exit 1
fi

# ------------------------------------------------------------------------------
# 3. docker compose 기동
# ------------------------------------------------------------------------------
log "2/8 docker compose 기동"
docker compose -f compose-test.yaml up -d

# ------------------------------------------------------------------------------
# 4. MySQL healthy 대기
# ------------------------------------------------------------------------------
log "3/8 MySQL healthy 대기"
until docker exec raillo-test-mysql mysqladmin ping -uroot -proot-local --silent 2>/dev/null; do
  sleep 2
done
log "    MySQL ready"

# ------------------------------------------------------------------------------
# 5. raillo-server /actuator/health UP 대기
# ------------------------------------------------------------------------------
log "4/8 raillo-server 기동 대기"
for i in $(seq 1 60); do
  if curl -s -f http://localhost:8080/actuator/health >/dev/null 2>&1; then
    log "    raillo-server UP"
    break
  fi
  sleep 3
done
if ! curl -s -f http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "ERROR: raillo-server 3분 안에 기동 실패. docker logs raillo-server 확인" >&2
  exit 1
fi

# ------------------------------------------------------------------------------
# 6. batch trainInitialize
#    스케줄이 이미 있으면 스킵 (batch job 자체는 idempotent 하지 않을 수 있으므로 DB에 데이터 있는지로 판단)
# ------------------------------------------------------------------------------
log "5/8 batch trainInitialize (역·열차·좌석·스케줄 적재)"
schedule_count=$(docker exec raillo-test-mysql mysql -uraillo -praillo-local raillo -Nse "SELECT COUNT(*) FROM train_schedule" 2>/dev/null || echo "0")
if [ "$schedule_count" -gt 0 ]; then
  log "    이미 스케줄 $schedule_count 건 존재. 스킵."
else
  docker compose -f compose-test.yaml --profile batch run --rm \
    raillo-batch --job=trainInitialize
fi

# ------------------------------------------------------------------------------
# 7. 회원 100명 생성
# ------------------------------------------------------------------------------
log "6/8 회원 100명 생성"
member_count=$(docker exec raillo-test-mysql mysql -uraillo -praillo-local raillo -Nse "SELECT COUNT(*) FROM member" 2>/dev/null || echo "0")
if [ "$member_count" -ge 100 ]; then
  log "    이미 회원 $member_count 명 존재. 스킵."
else
  # 필수 파이썬 패키지 확인
  python3 -c "import pymysql, bcrypt, faker, cryptography" 2>/dev/null || \
    pip3 install pymysql bcrypt faker cryptography --quiet
  python3 qa/db-scripts/generate_members.py \
    --host localhost --port 3306 \
    --user raillo --password raillo-local \
    --total 100 --batch 100
fi

# ------------------------------------------------------------------------------
# 8. k6 schedule-config.json 생성
# ------------------------------------------------------------------------------
log "7/8 k6 스케줄 config 생성"
python3 qa/db-scripts/generate_k6_schedule_config.py \
  --host localhost --port 3306 \
  --user raillo --password raillo-local

# ------------------------------------------------------------------------------
# 완료
# ------------------------------------------------------------------------------
log "8/8 완료"
echo ""
echo "=========================================="
echo " 부트스트랩 완료. 이제 k6를 실행할 수 있습니다:"
echo ""
echo "   K6_WEB_DASHBOARD=true k6 run qa/k6/payment-metrics-test.js"
echo ""
echo " 대시보드:"
echo "   Grafana:    http://localhost:3000/d/payment-metrics/payment-metrics"
echo "   Prometheus: http://localhost:9090"
echo "   WireMock:   http://localhost:8081/__admin"
echo ""
echo " 정리:"
echo "   docker compose -f compose-test.yaml down      # 컨테이너만"
echo "   docker compose -f compose-test.yaml down -v   # 볼륨(MySQL 데이터)까지"
echo "=========================================="
