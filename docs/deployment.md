# Deployment

## Environments

| Environment | Database | Redis                       | Profile |
|-------------|----------|-----------------------------|---------|
| Local Dev | MySQL (docker-compose) | Redis (docker-compose)      | `dev` |
| Test | H2 (in-memory) | Embedded Redis (port 63790) | `test` |
| Production | AWS RDS (MySQL) | ElastiCache (Valkey)        | `prod` |

## Local Development

```bash
# Start MySQL and Redis containers
docker-compose up -d

# Run application (dev profile is default)
./gradlew bootRun
```

`compose.yaml` provides:
- MySQL: `chanwon2/raillo-db-with-data` (pre-loaded data), port 3306
- Redis: `redis:latest`, port 6379

## CI/CD Pipeline

```
GitHub (develop branch)
    ↓ push/PR triggers
GitHub Actions (build & test)
    ↓ Docker image build
AWS ECR (container registry)
    ↓ ArgoCD sync
AWS EKS (Kubernetes cluster)
```

### GitHub Actions (`.github/workflows/gradle_build_and_test.yml`)
- Triggers on push/PR to `develop`
- Java 17 (Temurin), Gradle build
- Runs tests, publishes JUnit reports

## Production (AWS EKS)

### Infrastructure
- **Cluster**: AWS EKS (ap-northeast-2)
- **Container Registry**: AWS ECR
- **Database**: AWS RDS (MySQL)
- **Domain**: `server.raillo.shop` (Route53)
- **TLS**: cert-manager with Let's Encrypt

### Kubernetes Resources (`k8s/`)
- `depl_svc.yml` — Backend Deployment (2 replicas) + ClusterIP Service
- `redis.yml` — Redis Deployment + Service
- `ingress.yml` — NGINX Ingress with TLS
- `https.yml` — Certificate configuration

### ArgoCD GitOps (`k8s-argocd/`)
- Auto-sync from `main` branch
- Prune: deletes removed resources
- Self-heal: reverts manual cluster changes

### Backend Pod Configuration
- Replicas: 2
- Resources: 0.5-1 CPU, 512Mi-1Gi memory
- Health check: `/health` endpoint
- Secrets: `raillo-secrets` (DB, JWT, Mail credentials)

## Docker

Multi-stage build (`Dockerfile`):

```dockerfile
# Stage 1: Build with Gradle
FROM openjdk:17-jdk-alpine AS stage1
# ... gradle build ...

# Stage 2: Runtime
FROM openjdk:17-jdk-alpine
ENV TZ=Asia/Seoul
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
```
