# Deployment

## Environments

| Environment | Database | Redis | Profile |
|-------------|----------|-------|---------|
| Local Dev | 외부 Test DB (MySQL, `${TEST_DB_URL}`) | Redis (`compose.yaml`, port 6379) | `dev` |
| Test | Testcontainers MySQL 8.4.10 | Testcontainers Redis 7.4 | `test` |
| Production | AWS RDS (MySQL 8.4.10) | Redis 7.4 (K8s Pod) | `prod` |

## Local Development

```bash
# Redis 컨테이너 기동 (compose.yaml은 Redis만 제공)
docker-compose up -d

# API 실행 (dev 프로파일이 기본값)
./gradlew :raillo-api:bootRun

# Batch 실행 예시
./gradlew :raillo-batch:bootRun --args='--spring.batch.job.name=trainDailyScheduleJob --run.id=1'
```

- `compose.yaml` 은 **Redis**(`redis:latest`, port 6379) 만 제공한다.
- `dev` 프로파일은 `${TEST_DB_URL}` 환경변수로 **외부 Test DB(MySQL)** 에 연결한다 (`.env` 등으로 주입).
- 부하 테스트용 풀스택(Spring Boot + Redis + WireMock + Prometheus + Grafana)은 별도의 `compose-test.yaml` 로 띄운다 (README의 "로컬 부하 테스트 환경" 참조).

## CI/CD Pipeline

```
develop 브랜치 push/PR
    └─> GitHub Actions: gradle_build_and_test.yml  (build & test)

main 브랜치 push
    ├─> GitHub Actions: deploy_raillo_with_k8s.yml (API/Batch Docker build → ECR push → API rollout restart)
    └─> ArgoCD: k8s/k8s-application 매니페스트 auto-sync
            ↓
        AWS EKS (raillo-cluster, ap-northeast-2)
```

### GitHub Actions (`.github/workflows/`)
- **`gradle_build_and_test.yml`** — `develop` 으로의 push/PR에서 실행. Java 25 (Temurin), Gradle 빌드, 테스트 수행 및 JUnit 리포트 발행.
- **`deploy_raillo_with_k8s.yml`** — `main` push에서 실행. API 이미지를 `raillo-backend`, Batch 이미지를 `raillo-batch` ECR 저장소에 빌드·푸시한 뒤 API Deployment를 재시작한다.

## Production (AWS EKS)

### Infrastructure
- **Cluster**: AWS EKS `raillo-cluster` (ap-northeast-2)
- **Container Registry**: AWS ECR
- **Database**: AWS RDS (MySQL 8.4.10)
- **Redis**: `redis:7.4-alpine`
- **Domain**: `server.raillo.store`
- **TLS**: cert-manager (`raillo-issuer` ClusterIssuer, Let's Encrypt) → Secret `server-raillo-com-tls`

### Kubernetes Resources (`k8s/`)
매니페스트는 용도별 디렉터리로 분리되어 있다.

- **`k8s/k8s-application/`** — 애플리케이션
  - `depl_svc.yml` — Backend Deployment (2 replicas) + ClusterIP Service
  - `batch-cronjobs.yml` — 열차 일일 스케줄(KST 02:00), 만료 회원 삭제(KST 03:00)
  - `ingress.yml` — NGINX Ingress (`server.raillo.store`, TLS)
  - `https.yml` — cert-manager ClusterIssuer + Certificate
- **`k8s/k8s-argocd/`** — ArgoCD
  - `argocd-application.yml` — ArgoCD Application 정의
  - `argocd-ingress.yml` / `argocd-https.yml` — ArgoCD 대시보드 Ingress + 인증서
- **`k8s/k8s-monitoring/`** — 관측 스택
  - `prometheus-depl_svc.yaml` / `prometheus-config.yml` / `prometheus-rbac.yml`
  - `grafana-depl_svc.yml`, `node-exporter.yml`
  - `monitoring-ingress.yml` / `monitoring-https.yml`

### ArgoCD GitOps (`k8s/k8s-argocd/`)
- Application `raillo-backend` (namespace `argocd` → 대상 namespace `raillo`)
- Source: `github.com/penggu-dev/raillo-backend`, `targetRevision: main`, `path: k8s/k8s-application`
- `syncPolicy.automated`:
  - **prune**: Git에서 삭제된 리소스를 클러스터에서도 자동 삭제
  - **selfHeal**: 클러스터 수동 변경을 Git 상태로 자동 복구

### Backend Pod Configuration (`k8s/k8s-application/depl_svc.yml`)
- Replicas: 2 (`revisionHistoryLimit: 2`)
- **Pod 분산**: `topologySpreadConstraints` (`kubernetes.io/hostname`, `topology.kubernetes.io/zone`, `maxSkew: 1`, `ScheduleAnyway`)
- Resources: requests 0.5 CPU / 512Mi, limits 1 CPU / 1Gi
- Image: ECR `raillo-backend:latest`, containerPort 8080
- **무중단 배포**: `readinessProbe` → `GET /health:8080` (initialDelay 10s, period 10s)
- **환경변수 주입**: `envFrom` 으로 ConfigMap `raillo-config` + Secret `raillo-secrets` 주입 (둘 다 클러스터에서 외부 관리, repo 매니페스트 없음)
- Service: ClusterIP, port 80 → targetPort 8080

### Batch CronJobs

- `train-daily-schedule`: `trainDailyScheduleJob`, 매일 KST 02:00
- `delete-expired-members`: `deleteExpiredMembersJob`, 매일 KST 03:00
- `concurrencyPolicy: Forbid`로 동일 Job의 중복 실행을 막는다.
- 각 실행은 `run.id=$(date +%s)`를 전달하며, 날짜 생성은 기존 날짜 조회를 통해 멱등성을 유지한다.
- Batch 컨테이너도 `raillo-config`와 `raillo-secrets`를 `envFrom`으로 주입받는다.

## Spring Batch 메타테이블

운영에서는 `spring.batch.jdbc.initialize-schema=never`, 테스트에서는 `always`를 사용한다. 최초 배포 전에 관리자 계정으로 다음 SQL을 대상 스키마에 한 번 적용한다.

```bash
mysql -h <host> -u <admin> -p <database> < docs/db-scripts/spring-batch-schema-mysql.sql
```

적용 후 Batch 실행 계정에 비즈니스 테이블과 `BATCH_%` 메타테이블을 읽고 쓸 권한이 있는지 확인한다. 고정 배포 순서는 다음과 같다.

1. `docs/db-scripts/spring-batch-schema-mysql.sql` 적용
2. API·Batch 이미지 배포
3. `k8s/k8s-application/batch-cronjobs.yml` 적용 또는 ArgoCD sync 확인
4. 아래 수동 Job으로 검증

```bash
kubectl create job --from=cronjob/train-daily-schedule train-daily-schedule-manual-$(date +%s) -n raillo
kubectl create job --from=cronjob/delete-expired-members delete-expired-members-manual-$(date +%s) -n raillo
```

자동 스케줄하지 않는 parse/month/init Job은 필요할 때 다음 형태로 실행한다.

```bash
kubectl run train-parse-$(date +%s) -n raillo --restart=Never \
  --image=052104148083.dkr.ecr.ap-northeast-2.amazonaws.com/raillo-batch:latest \
  --env="DB_URL=<jdbc-url>" --env="DB_USERNAME=<username>" --env="DB_PW=<password>" \
  -- java -jar /app/app.jar --spring.batch.job.name=trainParseJob --run.id=$(date +%s)

# trainMonthlyScheduleJob, trainInitializeJob도 job.name만 바꿔 같은 방식으로 실행한다.
```

실제 운영에서는 명령행에 DB 비밀번호를 직접 넣지 말고 `raillo-config`/`raillo-secrets`를 참조하는 일회성 Job 매니페스트를 사용한다.

## Docker

API는 `Dockerfile`에서 `:raillo-api:bootJar`, Batch는 `Dockerfile.batch`에서 `:raillo-batch:bootJar`를 빌드한다.

```dockerfile
# Stage 1: Build with Gradle
FROM eclipse-temurin:25-jdk-alpine AS stage1
WORKDIR /app
# ... copy sources, ./gradlew bootJar ...

# Stage 2: Runtime
FROM eclipse-temurin:25-jdk-alpine
RUN apk add --no-cache tzdata
ENV TZ=Asia/Seoul
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"
COPY --from=stage1 /app/raillo-api/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
```
