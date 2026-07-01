# Booking Performance Manual Run Guide

이 문서는 `v1` 예약 API와 `develop(v2)` 예약 API를 같은 방식으로 수동 성능 테스트하기 위한 실행 순서다.

## 전제

- 현재 작업 브랜치: `booking-performance-setup`
- 아래 명령은 모두 `raillo-backend/qa` 디렉터리 기준 예시다.
- Python 준비/비교 스크립트는 IntelliJ에서 Working directory가 `qa/`로 잡히는 기준에 맞춘다.
- 테스트 DB schema:
  - `v1` 브랜치: `v1`
  - `develop` 브랜치: `v2`
- 두 schema에는 열차 데이터와 테스트 회원 1000명이 이미 들어 있다.
- 테스트 회원 비밀번호: `Test1234!`
- 회원번호 후보 범위: `202603030001` ~ `202603039999`
- v1 회원 데이터는 `is_locked=0`, `lock_count=0`이 필요하다. 값이 `NULL`이면 v1 로그인 시 Hibernate가 `MemberDetail`을 만들지 못한다.
- 성능 테스트 전 선점 데이터: 대상 좌석 30%
- 로컬 앱 실행은 `compose-test.yaml`을 사용한다.
- 로컬 앱 컨테이너 스펙:
  - app: 1 vCPU, 1GB memory
  - Redis: 0.5 vCPU, 768MB memory, `maxmemory=512mb`, `noeviction`
  - Tomcat max threads: 200
  - Hikari maximum pool size: 100
- 실제 k6 실행은 사용자가 직접 수행한다.
- 결과는 환경별로 분리 저장한다.
  - 로컬: `results/booking-performance/local/`
  - EKS: `results/booking-performance/eks/`

## 1. 로컬 env 파일

성능 테스트용 env 파일은 루트 `.env`를 직접 수정하지 않고 별도로 둔다.

- `env/booking-performance-v1.env`: v1 앱 실행 및 v1 schema 준비용
- `env/booking-performance-develop.env`: develop 앱 실행 및 v2 schema 준비용

실제 env 파일은 Git에 포함하지 않는다. 필요한 항목은 아래 예시 파일을 기준으로 맞춘다.

- `env/booking-performance-v1.env.example`
- `env/booking-performance-develop.env.example`

IntelliJ Run Configuration으로 `db-scripts/generate_booking_performance_data.py`를 직접 실행할 때는 Working directory를 `raillo-backend/qa`로 둔다. Program arguments에는 문서의 `--env-file env/...`, `--output k6/...`, `--seed-report results/...` 값을 그대로 넣는다.

`compose-test.yaml`은 이 테스트 세팅 브랜치(`booking-performance-setup`)에만 있다. 따라서 v1 성능 테스트도 v1 브랜치 안에서 compose를 실행하지 않고, 이 브랜치의 `qa/` 디렉터리에서 compose를 실행한 뒤 v1 worktree의 jar만 연결한다.

`compose-test.yaml`에서 사용하는 `APP_ENV_FILE`, `APP_LIBS_DIR` 값은 compose 파일 위치인 `qa/` 기준 상대 경로다. 예를 들어 v1 env 파일은 `./env/booking-performance-v1.env`, v1 jar 디렉터리는 `../.worktrees/raillo-v1/build/libs`로 지정한다.

v1 브랜치는 예전 프로젝트명 때문에 jar 이름이 `railo-0.0.1-SNAPSHOT.jar`다. develop은 `raillo-0.0.1-SNAPSHOT.jar`를 사용한다.

## 2. 로컬 DB 준비

예약 API는 성공 요청이 실제 예약 데이터를 추가한다. 따라서 같은 DB 상태로 k6를 3번 연속 실행하면 2, 3회차는 점점 충돌 위주의 테스트로 바뀐다.

공정한 반복 측정을 위해 실제 측정 루프에서는 **각 run 직전마다 MySQL 예약/결제 데이터를 초기화하고 30% 선점 데이터를 다시 만든 뒤, Redis도 비운다.** 아래 DB 준비 명령은 단독 검증용이며, 실제 3회 측정 루프에도 동일하게 포함되어 있다.

`generate_booking_performance_data.py`는 DB 기준 현재시각에서 기본 30분 뒤(`--departure-buffer-minutes 30`) 이후에 출발하는 `ACTIVE`/`DELAYED` 스케줄만 선택한다. develop은 출발 5분 전부터 예약을 막기 때문에, 과거 스케줄이 선택되면 k6에서 `TRAIN_402`가 발생한다.

k6 config는 `k6/config/booking-performance-config.json` 한 파일만 사용한다. 각 run 직전에 현재 테스트할 브랜치 기준으로 다시 생성하므로, v1과 develop config를 동시에 보관하지 않는다.

### v1 DB 준비

```bash
python3 db-scripts/generate_booking_performance_data.py \
  --branch v1 \
  --schema v1 \
  --env-file env/booking-performance-v1.env \
  --confirm-test-db \
  --occupancy 0.30 \
  --member-limit 1000 \
  --output k6/config/booking-performance-config.json \
  --seed-report results/booking-performance/local/seed-report-v1.md
```

### develop DB 준비

```bash
python3 db-scripts/generate_booking_performance_data.py \
  --branch develop \
  --schema v2 \
  --env-file env/booking-performance-develop.env \
  --confirm-test-db \
  --occupancy 0.30 \
  --member-limit 1000 \
  --output k6/config/booking-performance-config.json \
  --seed-report results/booking-performance/local/seed-report-develop.md
```

## 3. 로컬 v1 서버 실행

아래 명령은 모두 `booking-performance-setup` 브랜치의 `qa/` 디렉터리에서 실행한다. v1 브랜치로 직접 checkout해서 실행하지 않는다.

v1 브랜치는 현재 작업 브랜치와 API/스키마가 다르므로, v1 jar를 별도 worktree에서 빌드하고 compose에는 해당 jar 디렉터리만 연결한다.

```bash
git switch booking-performance-setup
```

```bash
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

이때 실행되는 compose 파일은 현재 브랜치의 `compose-test.yaml`이고, 앱 컨테이너 안에서 실행되는 jar만 v1 worktree의 빌드 결과물이다.

테스트가 끝나면 다음 버전 실행 전에 compose를 내린다.

```bash
docker compose -f compose-test.yaml down
```

## 4. 로컬 v1 테스트

`v1` 서버를 띄운 뒤 실행한다. 각 run은 `prepare -> Redis flush -> k6` 순서로 진행한다.

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --branch v1 \
    --schema v1 \
    --env-file env/booking-performance-v1.env \
    --confirm-test-db \
    --occupancy 0.30 \
    --member-limit 1000 \
    --output k6/config/booking-performance-config.json \
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

## 5. 로컬 develop 서버 실행

develop 테스트는 `booking-performance-setup` 브랜치의 jar를 빌드해 실행한다. 이 브랜치는 develop 기반 테스트 세팅 브랜치이므로 develop API 테스트에 사용할 수 있다.

```bash
../gradlew clean bootJar
```

```bash
APP_ENV_FILE=./env/booking-performance-develop.env \
docker compose -f compose-test.yaml up -d
```

테스트가 끝나면 compose를 내린다.

```bash
docker compose -f compose-test.yaml down
```

## 6. 로컬 develop 테스트

`develop` 서버를 띄운 뒤 실행한다. 각 run은 `prepare -> Redis flush -> k6` 순서로 진행한다.

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --branch develop \
    --schema v2 \
    --env-file env/booking-performance-develop.env \
    --confirm-test-db \
    --occupancy 0.30 \
    --member-limit 1000 \
    --output k6/config/booking-performance-config.json \
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

## 7. 로컬 결과 비교

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

## 8. EKS 테스트 준비

EKS에서는 같은 k6 스크립트와 같은 config를 사용한다. 단, `BASE_URL`과 결과 저장 경로만 바꾼다.

예상 파드 배치:

```text
node1
- v1-1
- v1-2

node2
- v2-1
- v2-2
```

EKS에서 테스트 전에도 DB 선점 조건을 맞춘다. EKS가 같은 테스트 DB를 사용한다면 아래 명령을 다시 실행해 예약/결제 데이터만 초기화하고 30% 선점 데이터를 다시 만든다.

```bash
python3 db-scripts/generate_booking_performance_data.py \
  --branch v1 \
  --schema v1 \
  --env-file env/booking-performance-v1.env \
  --confirm-test-db \
  --occupancy 0.30 \
  --member-limit 1000 \
  --output k6/config/booking-performance-config.json \
  --seed-report results/booking-performance/eks/seed-report-v1.md
```

```bash
python3 db-scripts/generate_booking_performance_data.py \
  --branch develop \
  --schema v2 \
  --env-file env/booking-performance-develop.env \
  --confirm-test-db \
  --occupancy 0.30 \
  --member-limit 1000 \
  --output k6/config/booking-performance-config.json \
  --seed-report results/booking-performance/eks/seed-report-develop.md
```

## 9. EKS v1 테스트

아래 `BASE_URL`은 EKS 내부 k6 Job 또는 클러스터 내부 Pod에서 실행하는 기준이다. 로컬 PC에서 직접 k6를 실행한다면 Service DNS 대신 Ingress 또는 Load Balancer 주소를 사용한다. EKS에서도 각 run 직전에 DB prepare와 Redis 초기화를 수행한다.

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --branch v1 \
    --schema v1 \
    --env-file env/booking-performance-v1.env \
    --confirm-test-db \
    --occupancy 0.30 \
    --member-limit 1000 \
    --output k6/config/booking-performance-config.json \
    --seed-report results/booking-performance/eks/seed-report-v1-run-${i}.md

  # EKS Redis 위치에 맞게 실행한다.
  # 예: kubectl exec -n <namespace> <redis-pod> -- redis-cli FLUSHALL

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

## 10. EKS develop 테스트

아래 `BASE_URL`은 EKS 내부 k6 Job 또는 클러스터 내부 Pod에서 실행하는 기준이다. 로컬 PC에서 직접 k6를 실행한다면 Service DNS 대신 Ingress 또는 Load Balancer 주소를 사용한다. EKS에서도 각 run 직전에 DB prepare와 Redis 초기화를 수행한다.

```bash
for i in 1 2 3; do
  python3 db-scripts/generate_booking_performance_data.py \
    --branch develop \
    --schema v2 \
    --env-file env/booking-performance-develop.env \
    --confirm-test-db \
    --occupancy 0.30 \
    --member-limit 1000 \
    --output k6/config/booking-performance-config.json \
    --seed-report results/booking-performance/eks/seed-report-develop-run-${i}.md

  # EKS Redis 위치에 맞게 실행한다.
  # 예: kubectl exec -n <namespace> <redis-pod> -- redis-cli FLUSHALL

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

## 11. EKS 결과 비교

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

## 시나리오 옵션

`SCENARIO` 값:

- `high-contention`: 기본값. 선점 좌석과 빈 좌석을 섞어 같은 전체 구간으로 요청한다.
- `sold-conflict`: 이미 선점된 좌석만 요청해 확정 예약 충돌 비용을 본다.
- `open-only`: 빈 좌석만 요청해 임시 선점 동시성 비용을 본다.
- `section-aware`: 같은 좌석 후보에서 앞 구간/뒤 구간을 번갈아 요청한다.

## 안전장치

`generate_booking_performance_data.py`는 예약/결제 데이터를 삭제하므로 반드시 `--confirm-test-db`를 요구한다. 운영 데이터가 연결된 DB에는 실행하지 않는다.

seed report와 k6 config에는 선택된 스케줄의 `operationDate`, `departureTime`, `operationStatus`가 기록된다. `TRAIN_402`가 보이면 먼저 해당 값이 테스트 시점보다 충분히 미래인지 확인하고, 장시간 테스트라면 `--departure-buffer-minutes` 값을 늘린다.
