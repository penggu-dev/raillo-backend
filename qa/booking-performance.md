# 예약 API 성능 테스트 실행 가이드

모든 명령은 `raillo-backend/qa` 디렉터리에서 실행한다.

```bash
cd /Users/chanwon/project/raillo/raillo-backend/qa
```

## 기준

- env 파일: `env/booking-performance-v1.env`, `env/booking-performance-develop.env`
- k6 config: `k6/config/booking-performance-config.json`
- 로컬 결과: `results/booking-performance/local/`
- EKS 결과: `results/booking-performance/eks/`
- k6 script: `k6/booking-performance-test.js`
- 테스트 회원 비밀번호: `Test1234!`
- 테스트 회원번호: `202603030001` ~ `202603039999`

`--env-file`의 `TEST_DB_URL`로 DB/schema를 선택한다. 실행 명령에는 `--schema`를 넣지 않는다.

## 공통 Config 생성

v1/v2 schema의 열차/회원 데이터가 같으므로 config는 하나만 사용한다. 브랜치별 API 선택은 k6 실행 시 `BRANCH=v1|develop`로 결정한다.

```bash
python3 db-scripts/generate_booking_performance_data.py \
  --mode config \
  --env-file env/booking-performance-develop.env \
  --occupancy 0.30 \
  --member-limit 1000 \
  --output k6/config/booking-performance-config.json
```

## 로컬 v1 서버

v1 jar를 별도 worktree에서 빌드하고 현재 `compose-test.yaml`로 실행한다.

```bash
git switch booking-performance-setup
git worktree remove --force ../.worktrees/raillo-v1 2>/dev/null || true
git worktree prune
rm -rf ../.worktrees/raillo-v1
mkdir -p ../.worktrees
git worktree add ../.worktrees/raillo-v1 v1
```

```bash
cd ../.worktrees/raillo-v1
./gradlew clean bootJar
cd ../../qa
```

```bash
APP_LIBS_DIR=../.worktrees/raillo-v1/build/libs \
APP_JAR_FILE=railo-0.0.1-SNAPSHOT.jar \
APP_ENV_FILE=./env/booking-performance-v1.env \
docker compose -f compose-test.yaml up -d
```

## 로컬 v1 테스트

각 run은 `DB 준비 -> Redis 초기화 -> k6` 순서로 실행한다.

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --mode prepare \
    --branch v1 \
    --env-file env/booking-performance-v1.env \
    --confirm-test-db \
    --config k6/config/booking-performance-config.json \
    --seed-report results/booking-performance/local/seed-report-v1-run-${i}.md

  APP_ENV_FILE=./env/booking-performance-v1.env \
  docker compose -f compose-test.yaml exec -T redis redis-cli FLUSHALL

  BRANCH=v1 \
  CONFIG=config/booking-performance-config.json \
  BASE_URL=http://localhost:8080 \
  SCENARIO=high-contention \
  SUMMARY_PATH=results/booking-performance/local/v1-run-${i}.json \
  k6 run k6/booking-performance-test.js
done
```

```bash
docker compose -f compose-test.yaml down
```

## 로컬 develop 서버

```bash
../gradlew -p .. clean bootJar
```

```bash
APP_ENV_FILE=./env/booking-performance-develop.env \
docker compose -f compose-test.yaml up -d
```

## 로컬 develop 테스트

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --mode prepare \
    --branch develop \
    --env-file env/booking-performance-develop.env \
    --confirm-test-db \
    --config k6/config/booking-performance-config.json \
    --seed-report results/booking-performance/local/seed-report-develop-run-${i}.md

  APP_ENV_FILE=./env/booking-performance-develop.env \
  docker compose -f compose-test.yaml exec -T redis redis-cli FLUSHALL

  BRANCH=develop \
  CONFIG=config/booking-performance-config.json \
  BASE_URL=http://localhost:8080 \
  SCENARIO=high-contention \
  SUMMARY_PATH=results/booking-performance/local/develop-run-${i}.json \
  k6 run k6/booking-performance-test.js
done
```

```bash
docker compose -f compose-test.yaml down
```

## 로컬 결과 비교

```bash
python3 db-scripts/generate_booking_performance_report.py \
  --environment local \
  --v1 \
    results/booking-performance/local/v1-run-1.json \
    results/booking-performance/local/v1-run-2.json \
    results/booking-performance/local/v1-run-3.json \
  --develop \
    results/booking-performance/local/develop-run-1.json \
    results/booking-performance/local/develop-run-2.json \
    results/booking-performance/local/develop-run-3.json \
  --seed-v1 results/booking-performance/local/seed-report-v1-run-1.md \
  --seed-develop results/booking-performance/local/seed-report-develop-run-1.md \
  --output results/booking-performance/local/comparison.md
```

## EKS v1 테스트

`BASE_URL`은 EKS 내부 Service DNS 기준이다. 외부에서 실행하면 Ingress/LB 주소로 바꾼다.

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --mode prepare \
    --branch v1 \
    --env-file env/booking-performance-v1.env \
    --confirm-test-db \
    --config k6/config/booking-performance-config.json \
    --seed-report results/booking-performance/eks/seed-report-v1-run-${i}.md

  # kubectl exec -n <namespace> <redis-pod> -- redis-cli FLUSHALL

  BRANCH=v1 \
  CONFIG=config/booking-performance-config.json \
  BASE_URL=http://raillo-v1.v1.svc.cluster.local \
  SCENARIO=high-contention \
  RAMP_UP=10s \
  DURATION=40s \
  RAMP_DOWN=10s \
  SUMMARY_PATH=results/booking-performance/eks/v1-run-${i}.json \
  k6 run k6/booking-performance-test.js
done
```

## EKS develop 테스트

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --mode prepare \
    --branch develop \
    --env-file env/booking-performance-develop.env \
    --confirm-test-db \
    --config k6/config/booking-performance-config.json \
    --seed-report results/booking-performance/eks/seed-report-develop-run-${i}.md

  # kubectl exec -n <namespace> <redis-pod> -- redis-cli FLUSHALL

  BRANCH=develop \
  CONFIG=config/booking-performance-config.json \
  BASE_URL=http://raillo-v2.v2.svc.cluster.local \
  SCENARIO=high-contention \
  RAMP_UP=10s \
  DURATION=40s \
  RAMP_DOWN=10s \
  SUMMARY_PATH=results/booking-performance/eks/develop-run-${i}.json \
  k6 run k6/booking-performance-test.js
done
```

## EKS 결과 비교

```bash
python3 db-scripts/generate_booking_performance_report.py \
  --environment eks \
  --v1 \
    results/booking-performance/eks/v1-run-1.json \
    results/booking-performance/eks/v1-run-2.json \
    results/booking-performance/eks/v1-run-3.json \
  --develop \
    results/booking-performance/eks/develop-run-1.json \
    results/booking-performance/eks/develop-run-2.json \
    results/booking-performance/eks/develop-run-3.json \
  --seed-v1 results/booking-performance/eks/seed-report-v1-run-1.md \
  --seed-develop results/booking-performance/eks/seed-report-develop-run-1.md \
  --output results/booking-performance/eks/comparison.md
```

## 옵션

- `SCENARIO=high-contention`: 기본. 선점 좌석과 빈 좌석을 섞어 요청
- `SCENARIO=sold-conflict`: 선점 좌석만 요청
- `SCENARIO=open-only`: 빈 좌석만 요청
- `SCENARIO=section-aware`: 앞 구간/뒤 구간을 번갈아 요청

`TRAIN_402`가 나오면 테스트 대상 스케줄 출발 시간이 지났는지 확인한다.
