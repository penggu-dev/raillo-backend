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

# 애플리케이션 실행 (dev 프로파일이 기본값)
./gradlew bootRun
```

- `compose.yaml` 은 **Redis**(`redis:latest`, port 6379) 만 제공한다.
- `dev` 프로파일은 `${TEST_DB_URL}` 환경변수로 **외부 Test DB(MySQL)** 에 연결한다 (`.env` 등으로 주입).
- 부하 테스트용 풀스택(Spring Boot + Redis + WireMock + Prometheus + Grafana)은 별도의 `compose-test.yaml` 로 띄운다 (README의 "로컬 부하 테스트 환경" 참조).

## CI/CD Pipeline

```
develop 브랜치 push/PR
    └─> GitHub Actions: gradle_build_and_test.yml  (build & test)

main 브랜치 push
    ├─> GitHub Actions: deploy_raillo_with_k8s.yml (Docker build → ECR push → kubectl rollout restart)
    └─> ArgoCD: k8s/k8s-application 매니페스트 auto-sync
            ↓
        AWS EKS (raillo-cluster, ap-northeast-2)
```

### GitHub Actions (`.github/workflows/`)
- **`gradle_build_and_test.yml`** — `develop` 으로의 push/PR에서 실행. Java 17 (Temurin), Gradle 빌드, 테스트 수행 및 JUnit 리포트 발행.
- **`deploy_raillo_with_k8s.yml`** — `main` push에서 실행. AWS 자격 증명 구성 → `aws eks update-kubeconfig --name raillo-cluster` → ECR 로그인 → Docker 이미지 빌드/푸시(`:latest`) → `kubectl rollout restart deployment raillo-backend -n raillo`.

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

## Docker

Multi-stage build (`Dockerfile`):

```dockerfile
# Stage 1: Build with Gradle
FROM eclipse-temurin:17-jdk-alpine AS stage1
WORKDIR /app
# ... copy sources, ./gradlew bootJar ...

# Stage 2: Runtime
FROM eclipse-temurin:17-jdk-alpine
RUN apk add --no-cache tzdata
ENV TZ=Asia/Seoul
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"
COPY --from=stage1 /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
```
