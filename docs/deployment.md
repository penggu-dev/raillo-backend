# Deployment

## Environments

| Environment | Database | Redis | Profile |
|-------------|----------|-------|---------|
| Local Dev | 외부 Test DB (MySQL, `${TEST_DB_URL}`) | Redis (`compose.yaml`, port 6379) | `dev` |
| Test | Testcontainers MySQL 8.4.10 | Testcontainers Valkey 9 | `test` |
| Production | MySQL 8.4 (OKE StatefulSet) | Valkey 9 (OKE StatefulSet) | `prod` |

## Local Development

```bash
# Redis 컨테이너 기동 (compose.yaml은 Redis만 제공)
docker-compose up -d

# 애플리케이션 실행 (dev 프로파일이 기본값)
./gradlew bootRun
```

- `compose.yaml` 은 **Redis 호환 서버 Valkey**(`valkey/valkey:9.0-alpine`, port 6379) 만 제공한다.
- `dev` 프로파일은 `${TEST_DB_URL}` 환경변수로 **외부 Test DB(MySQL)** 에 연결한다 (`.env` 등으로 주입).
- 부하 테스트용 풀스택(Spring Boot + Redis + WireMock + Prometheus + Grafana)은 별도의 `compose-test.yaml` 로 띄운다 (README의 "로컬 부하 테스트 환경" 참조).

## CI/CD Pipeline

```
develop push/PR
    └─> gradle_build_and_test.yml (build & test)
          └─ develop push에서 성공하면 (workflow_run)
               └─> deploy_raillo_with_k8s.yml
                     ├─ build : raillo-api·raillo-batch ARM64 이미지 → GHCR (:latest, :sha-<commit>)
                     └─ deploy: OKE kubeconfig 생성 → ConfigMap·Secret 존재 확인
                                → 이미지를 :sha-<commit>으로 치환 → kubectl apply → API rollout 대기
```

### GitHub Actions (`.github/workflows/`)
- **`gradle_build_and_test.yml`** — `develop` 으로의 push/PR에서 실행. Java 25 (Temurin), Gradle 빌드, 테스트 수행 및 JUnit 리포트 발행.
- **`deploy_raillo_with_k8s.yml`** — 위 테스트가 **develop push에서 성공했을 때만** 실행된다.
  - 배포 대상은 `k8s/oke/api-server/*.yaml`, `k8s/oke/batch/*.yaml`
  - 테스트한 커밋(`workflow_run.head_sha`)을 체크아웃하고 같은 sha로 이미지 태그를 붙인다.
  - CronJob은 다음 실행 시각부터 새 이미지를 쓴다.
  - 수동 실행(`workflow_dispatch`)은 develop 최신 커밋을 **테스트 없이** 배포한다. 긴급 배포용이다.
  - `kubectl apply`는 삭제를 반영하지 않는다. 저장소에서 지운 리소스는 클러스터에서 직접 삭제한다.

## Production (OCI OKE)

### Infrastructure
- **Cluster**: OKE (Kubernetes v1.36), 노드 `VM.Standard.A1.Flex`(2 OCPU, 12GB, ARM64) × 2
- **Container Registry**: GHCR (`ghcr.io/penggu-dev/raillo-api`, `ghcr.io/penggu-dev/raillo-batch`)
- **Load Balancer**: OCI Flexible LB 10Mbps. ingress-nginx의 `Service(type: LoadBalancer)`가 생성한다.
- **TLS**: ingress-nginx에서 종료. cert-manager `raillo-issuer`(Let's Encrypt, HTTP-01)가 Ingress 어노테이션으로 인증서를 발급한다.
- **Domain**: `server.raillo.site`(API), `monitoring.raillo.site`(Grafana)
