# Deployment

## 환경

| 환경 | Database | Redis | Profile |
|---|---|---|---|
| Local Dev | 외부 MySQL (`${DB_URL}`) | Valkey (`compose.yaml`) | `dev` |
| Test | Testcontainers MySQL 8.4.10 | Testcontainers Valkey 9 | `test` |
| Production | MySQL 8.4 (OKE) | Valkey 9 (OKE) | `prod` |

## 배포 자원 (OCI OKE)

| 자원 | 형태 | 비고 |
|---|---|---|
| API | Deployment 1개 | 이미지 `ghcr.io/penggu-dev/raillo-api` |
| Batch | CronJob 2개 | `train-daily-schedule`(03:00), `delete-expired-members`(04:00), 이미지 `ghcr.io/penggu-dev/raillo-batch` |
| MySQL | StatefulSet | OCI Block Volume 50Gi |
| Valkey | StatefulSet | AOF |
| Ingress | ingress-nginx (Helm) + cert-manager | OCI LB, Let's Encrypt TLS |
| 모니터링 | Prometheus, Grafana, exporter, kube-state-metrics | |

## CI/CD

```
develop push/PR
    └─> gradle_build_and_test.yml (build & test)
          └─ develop push에서 성공하면 (workflow_run)
               └─> deploy_raillo_with_k8s.yml
                     ├─ build : raillo-api·raillo-batch ARM64 이미지 → GHCR (:latest, :sha-<commit>)
                     └─ deploy: ConfigMap·Secret 존재 확인 → 이미지를 :sha-<commit>으로 치환
                                → k8s/oke/api-server·batch kubectl apply → API rollout 대기
```

- `gradle_build_and_test.yml`: `develop` push/PR에서 Java 25 빌드와 테스트를 실행한다.
- `deploy_raillo_with_k8s.yml`: 테스트가 develop push에서 성공했을 때만 실행한다. 테스트한 커밋과 같은 sha로 이미지를 태그한다.
  - CI는 `api-server`, `batch`만 적용한다. 나머지는 수동으로 적용한다.
  - `workflow_dispatch`는 develop 최신 커밋을 테스트 없이 배포한다. 긴급 배포용이다.
  - CronJob은 다음 실행 시각부터 새 이미지를 쓴다.
