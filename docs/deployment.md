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

## 런타임 포트와 헬스 체크

API 파드는 포트 두 개를 연다.

| 포트 | 이름 | 용도 |
|---|---|---|
| 8080 | `http` | 서비스 트래픽. Service와 Ingress가 가리킨다 |
| 8081 | `management` | actuator 전용. Service에 노출하지 않는다 |

`management.server.port`를 주면 Spring Boot가 별도 자식 컨텍스트에 독립된 커넥터와 스레드 풀을 만든다. 8080의 요청 스레드가 외부 호출 대기로 모두 묶여도 8081은 자기 풀로 응답한다. 프로브를 8080에 두면 포화할 때 헬스 체크까지 막혀 파드가 재시작되는데, 재시작은 외부 지연을 고치지 못하고 진행 중이던 결제만 끊는다.

| 프로브 | 경로 | 보는 것 | 주기 / 타임아웃 / 임계 |
|---|---|---|---|
| startup | `/actuator/health/liveness` | 기동 완료 여부 | 5s / 3s / 36회 |
| liveness | `/actuator/health/liveness` | `livenessState` | 20s / 5s / 3회 |
| readiness | `/actuator/health/readiness` | `readinessState` | 10s / 3s / 3회 |

나뉘는 것은 스레드 풀뿐이다. CPU와 메모리 제한은 컨테이너 단위이고 두 커넥터는 같은 JVM에 있어, CPU 포화나 긴 GC 정지는 두 포트에 함께 온다. 프로브 타임아웃이 그 몫의 여유다. 관리 커넥터가 더 쓰는 자원은 유휴 상태에서 스레드 12개(acceptor, poller, exec 10)이고 `management.server.*`에는 스레드 설정이 없어 따로 줄일 수 없다. 8081은 Service에 없어 kubelet과 Prometheus만 붙으므로 풀이 자랄 경로도 없다.

종합 `/actuator/health`는 `db`, `redis`, `mail`, `diskSpace`를 합산하므로 프로브에 쓰지 않는다. 의존성이 흔들릴 때마다 재시작되고 그 장애는 재시작으로 고쳐지지 않는다. 의존성 상태는 아래 알림이 다룬다. `replicas`가 1인 동안은 readiness도 의존성을 보지 않는다. 유일한 파드가 Service에서 빠지면 전면 중단이다. 스케일 아웃하면 다시 판단한다.

`startupProbe`가 성공할 때까지 나머지 두 프로브는 시작되지 않는다. `initialDelaySeconds` 기본값이 0이고 `livenessState`는 `ApplicationReadyEvent` 전까지 503이라, 이것이 없으면 기동 중에 세 번 실패해 40초에 재시작된다. 허용 상한은 5s × 36 = 180초다. 타임아웃이 3초, 5초로 짧은 것은 프로브가 메모리 상태만 읽기 때문이다. 8080의 종합 health를 보던 시절의 60초는 `periodSeconds`보다 길어 실효 간격이 타임아웃만큼 늘어났고, 죽은 JVM 감지에 3분이 걸렸다.

8080의 `/health`는 남아 있으나 프로브는 쓰지 않는다.

Prometheus 수집 대상도 8081이다. `prometheus-configmap.yaml`의 relabel이 컨테이너 포트 번호로 거르므로 포트를 바꾸면 정규식도 바꾼다. CI가 적용하지 않는 파일이고 리로드 엔드포인트도 꺼져 있어 수동으로 적용하고 재시작한다.

```bash
kubectl apply -f k8s/oke/monitoring/prometheus-configmap.yaml
kubectl -n monitoring rollout restart deployment prometheus
```

로컬 QA 스택(`compose-test.yaml`)도 같다. 내장 Prometheus는 컨테이너 간 통신이라 `raillo-server:8081`을 쓰고, 호스트에서는 publish한 8091을 쓴다. 호스트 8081은 WireMock이 점유한다. `qa/scripts/bootstrap-test-env.sh`의 기동 게이트도 8091의 readiness를 본다. readiness는 의존성을 보지 않으므로 MySQL과 Valkey 도달은 그 앞 단계에서 각각 `mysqladmin ping`과 `valkey-cli ping`으로 확인한다.

## 알림

Grafana 통합 알림으로 Discord에 보낸다. Alertmanager는 쓰지 않는다. Grafana가 이미 떠 있어 파드를 늘리지 않아도 되고 대시보드와 같은 곳에서 관리된다.

프로비저닝은 `grafana-alerting` configmap이며 `/etc/grafana/provisioning/alerting`에 마운트된다. Discord webhook URL은 `grafana-secret`의 `DISCORD_WEBHOOK_URL`을 `$__env{}`로 읽는다. 저장소에 URL을 두지 않는다.

| 규칙 | 조건 | 심각도 | noDataState |
|---|---|---|---|
| raillo-api 수집 불가 | `up{job="raillo-api"} < 1` 2분 | critical | 기본 |
| Valkey 수집 불가 | `up{job="valkey"} < 1` 2분 | critical | 기본 |
| MySQL 수집 불가 | `up{job="mysql"} < 1` 2분 | critical | 기본 |
| raillo-api DB 커넥션 획득 실패 | `increase(hikaricp_connections_timeout_total[10m]) > 0` 5분 | critical | OK |
| raillo-api 요청 스레드 포화 | `busy / config_max > 0.8` 2분 | critical | OK |
| raillo-api 파드 재시작 | 10분 내 재시작 발생 | warning | OK |
| raillo-api 스레드 지표 유실 | `absent(tomcat_threads_busy_threads)` 10분 | warning | OK |
| Toss 커넥션 풀 대기 | `toss_pool_connections_pending > 0` 2분 | warning | OK |
| Toss 승인 요청 미전송 | 10분 내 `phase="NOT_REACHED"` 실패, 5분 지속 | warning | OK |

수집 불가 세 규칙만 `noDataState` 기본값이다. 대상이 사라진 것 자체가 신호다. 나머지는 데이터 없음이 사건 없음이거나, 수집 공백이 곧 앱 부재여서 수집 불가 규칙과 겹친다.

수집 불가 규칙은 exporter 생존만 본다. exporter는 살아 있는데 앱이 닿지 못하는 경우는 앱 측 지표로 따로 본다. MySQL은 `hikaricp_connections_timeout_total`이 그 역할을 한다. 커넥션을 제한 시간 안에 얻지 못할 때 증가하므로 미도달과 풀 고갈을 함께 잡는다. Valkey는 앱이 내보내는 지표가 없어 아직 덮지 못한다.

알림 본문에 어느 파드인지 남기려면 `pod` 라벨이 필요하다. `prometheus-configmap.yaml`의 relabel이 `__meta_kubernetes_pod_name`을 `pod`으로 넣고, 파드별 규칙은 `max by (pod)`으로 집계한다. `instance`는 파드 IP라 읽기 어렵다.

스레드 포화 규칙은 포트 분리가 만든 공백을 메운다. 분리한 뒤에는 포화가 재시작을 일으키지 않고 `up`도 1로 유지돼, 따로 보지 않으면 조용한 장애가 된다. `tomcat_threads_*`는 `server.tomcat.mbeanregistry.enabled: true`가 있어야 등록되고 메인 커넥터만 보고한다. 비율로 보는 이유는 `TOMCAT_THREADS_MAX`가 환경마다 달라서다. 이 규칙은 `noDataState: OK`라 지표가 사라지면 조용히 무력화되므로, 지표 유실 규칙이 그 경우만 따로 본다. `spring.threads.virtual`을 켜면 `config_max`가 0이 되어 비율이 무의미해진다.

풀 대기 규칙은 미전송 규칙의 사각지대를 덮는다. `increase()`는 라벨 조합이 처음 등장할 때 기준 샘플이 없어 첫 실패를 놓치는데, 이 게이지는 기동 시점부터 존재한다. 풀 고갈이 미전송의 주된 원인이다.

규칙과 연락 지점은 Grafana UI에서 읽기 전용이다. 고치려면 configmap을 바꿔 다시 적용한다.

적용은 Secret이 먼저다. `DISCORD_WEBHOOK_URL`이 없으면 Grafana가 **기동에 실패한다.** 알림이 조용히 안 나가는 정도가 아니라 프로비저닝 모듈이 `could not find webhook url property in settings`로 죽고 프로세스가 종료된다. env는 기동 시점에만 읽히므로 Secret 없이 롤아웃하면 Grafana가 CrashLoopBackOff에 들어간다. `grafana-secret`은 `envFrom: secretRef`로 통째로 주입되며 다른 키가 들어 있어, 새 매니페스트에 없는 키를 지우는 `create --dry-run | apply` 대신 `patch`를 쓴다.

`grafana-alerting` configmap은 `grafana-deployment.yaml`이 마운트해야 읽힌다. 둘을 함께 적용하지 않으면 규칙이 들어가지 않는다.

```bash
kubectl -n monitoring patch secret grafana-secret \
  -p '{"stringData":{"DISCORD_WEBHOOK_URL":"<webhook url>"}}'
kubectl apply -f k8s/oke/monitoring/grafana-alerting-configmap.yaml
kubectl apply -f k8s/oke/monitoring/grafana-configmap.yaml
kubectl -n monitoring rollout restart deployment grafana
```

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
