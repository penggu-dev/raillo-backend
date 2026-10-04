# Payment Consistency

Toss(외부 결제 API), DB, Redis 세 시스템의 상태 정합성을 보장하기 위한 설계. 승인 흐름부터 적용하고, 취소 흐름은 후속 이슈 #259에서 확장한다.

> `OutboxWorker`는 이슈 #266에서 구현됐다. 승인 확정 후 Redis 정리는 이제 이 Worker가 담당한다. `PaymentRecoveryWorker`(승인·취소 attempt 대사·롤포워드·보상)는 이슈 #270의 범위이며 현재는 미구현이다.

## Why

결제 승인과 취소는 외부 API 호출과 내부 상태 변경(DB, Redis)을 함께 수행하는 유일한 흐름이다. 세 시스템은 한 트랜잭션으로 묶을 수 없으므로 특정 지점에서 실패하면 서로 다른 상태로 남는다.

개선 전 `PaymentConfirmService.confirm()`은 클래스 레벨 `@Transactional` 안에서 승인 API 호출과 이후 정리 작업을 함께 실행했다. 두 가지 실패 시나리오가 있었다.

### Scenario 1 — Toss 승인 후 DB 커밋 전 크래시

```
1. Toss confirm() → 승인 성공 (카드 실제 청구)
2. Order.completePayment(), Booking 생성, Payment.approve()
3. ← 서버 크래시 또는 DB 커밋 실패
4. Toss = 승인 완료, DB = 흔적 없음
   → 사용자는 결제했지만 승차권 없음
```

자동 회복 불가. CS 대응과 수동 환불로만 해소된다.

### Scenario 2 — Redis 정리 실패로 승인 롤백

```
1. Toss 승인 성공
2. Payment.approve(), Order.completePayment(), Booking 생성 완료
3. Reservation 정리 중 예외 발생
4. @Transactional이 전체 롤백
5. Toss = 승인, DB = 아무 것도 없음 (Scenario 1과 동일)
```

이슈 #257에서 지적된 지점이다. 현재는 승인 확정 트랜잭션 B에서 outbox 행을 INSERT하고 `PaymentOutboxWorker`가 트랜잭션 커밋 후 비동기로 Reservation 정리를 수행하므로, 정리 실패가 승인 결과를 되돌리지 않는다.

### 취소 흐름도 대칭

- Toss 취소 후 DB 커밋 전 크래시 → 환불 완료, 승차권 유효 상태로 잔존
- DB 취소 커밋 후 Redis 좌석 해제 실패 → 판매 가능 좌석이 hold 상태로 잔존

승인과 취소를 같은 정합성 프레임으로 다룬다.

## Why not events only

Spring `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)`로 정리를 트랜잭션 밖으로 옮기면 Scenario 2 롤백 위험은 해소된다. 그러나 두 가지가 남는다.

- 리스너 실행 전에 프로세스가 죽으면 이벤트 유실. 재시도 없음.
- Scenario 1은 이벤트 발행 자체가 없었던 상황이라 리스너 방식으로는 감지 불가.

이벤트 대신 DB 레코드 두 종류로 대체한다.

## Architecture — 후속 작업 완료 후 목표

```
┌─────────────────────────────────────────────────────────┐
│ Toss 호출 전                                            │
│   payment_attempt INSERT (status=IN_PROGRESS)           │
│                                                         │
│ Toss 호출 → 승인 또는 취소 성공                         │
│                                                         │
│ 결제 확정 트랜잭션                                      │
│   Payment / Order / Booking 상태 변경                   │
│   payment_attempt.status = SUCCEEDED                    │
│   payment_outbox INSERT (BookingConfirmed / Cancelled)  │
│ ← 커밋                                                  │
│                                                         │
│ OutboxWorker (별도 스케줄러)                            │
│   payment_outbox 폴링 → Redis 정리 → status=DONE        │
│                                                         │
│ PaymentRecoveryWorker (별도 스케줄러)                   │
│   IN_PROGRESS attempt 폴링 → Toss 조회 API로 대사       │
│   → 롤포워드 완료 또는 보상                             │
└─────────────────────────────────────────────────────────┘
```

## Components

### payment_attempt

Toss 호출 시도와 결과를 기록한다. 승인과 취소를 함께 담는 공용 테이블이며 `attempt_type` 컬럼으로 구분한다.

| Column | Purpose |
|--------|---------|
| `id` | DB PK (IDENTITY) |
| `payment_id` | 대상 결제 (FK 제약 없음, 다른 payment 엔티티 관례 준수) |
| `attempt_id` | 외부 idempotency key (아래 상세) |
| `attempt_type` | `APPROVAL` / `CANCELLATION` |
| `status` | `IN_PROGRESS` / `SUCCEEDED` / `FAILED` |
| `payment_key` | Toss 발급 결제 키 (Toss 조회 API 호출용) |
| `error_code`, `error_message` | 실패 정보 |
| `processing_owner`, `processing_lease_until` | Recovery Worker 동시 처리 방지 |
| `next_retry_at` | Recovery 재시도 예약 시각 |
| `created_at`, `updated_at` | JPA Auditing |

Toss 호출 직전에 `IN_PROGRESS`로 INSERT. 후속 `PaymentRecoveryWorker`는 오래 남은 이 row를 기준으로 Toss 결과와 DB 상태를 대사한다. row의 존재만으로 Toss 승인 성공을 단정하지 않는다.

승인과 취소를 공용으로 다루는 이유는 두 가지다. 컬럼 하나로 대칭 구조를 표현할 수 있고, Recovery Worker 로직도 하나로 통일된다. 승인에만 필요한 컬럼이 늘어난다면 별도 테이블 분리를 재검토한다.

#### `attempt_id`를 별도로 두는 이유

`id`(DB PK)가 이미 유일하므로 매 attempt가 새 row인 이상 `id`만으로 식별은 가능하다. 그럼에도 `attempt_id`(String, unique)를 별도 컬럼으로 두는 이유는 세 가지다.

- **내부 재시도 방어.** `startApprovalInNewTransaction`이 INSERT를 마쳤으나 응답이 유실되어 호출측이 재시도하는 경우, 같은 `attempt_id`로 unique 제약이 중복 삽입을 차단한다.
- **클라이언트 Idempotency-Key와의 연결.** 이슈 #260에서 클라이언트가 보내는 `Idempotency-Key` 헤더를 그대로 `attempt_id`에 저장하면 API 계층의 중복 방지와 도메인 계층의 시도 관리가 하나의 키로 이어진다.
- **관심사 분리.** `payment_key`는 Toss가 발급하는 외부 시스템 키, `attempt_id`는 우리 도메인의 시도 참조 키다. 하나로 뭉치면 PG 교체나 시도 이력 확장 시 스키마 변경 범위가 커진다.

현재 승인 API는 요청 body의 `attemptId`를 사용하며, 생략 시 `SHA-256("apv:" + paymentKey)`의
64자리 16진수 문자열을 사용한다. Idempotency-Key 헤더 연동은 #260 범위다.

#### 승인 재요청과 동시 실행 방어

- 같은 attemptId는 `paymentId`, `paymentKey`, `APPROVAL` 타입까지 일치해야 재사용할 수 있다. 불일치하면 `PAYMENT_114`, 64자를 초과한 입력은 API 검증 또는 애플리케이션의 `PAYMENT_115`로 거절한다.
- 성공한 시도는 DB에서 승인 결과 DTO를 직접 조회한다. 이미 로딩한 Payment 엔티티를 그대로 반환하지 않으므로, 다른 트랜잭션이 방금 승인한 결과도 반영한다. 최초 응답을 저장·재생하는 방식은 아니며 환불 등 이후 상태 변경도 반영한다.
- `PaymentAttemptManager.startApprovalInNewTransaction`은 짧은 `REQUIRES_NEW` 트랜잭션에서 Payment 행을 잠그고, 기존 승인 시도와 승인 가능 상태를 확인한 뒤 attempt를 `IN_PROGRESS`로 INSERT한다. `payment.payment_key`는 이 시점에 세팅하지 않는다. 잠금과 DB 트랜잭션은 Toss 호출 전에 해제한다.
- 반환값 `PaymentAttemptStartResult.created`가 true인 호출만 승인 API를 실행한다. 동일 시도의 재사용은 false로 반환하며, 다른 attemptId를 보내도 진행 중이거나 실패한 기존 승인을 우회할 수 없다.
- 실패한 결제를 재시도하려면 새 주문·결제를 준비한다. 기존 Payment가 FAILED/CANCELLED/REFUNDED이면 외부 호출 전에 거절한다.
- INSERT/커밋 무결성 오류 뒤에는 실제 동일 attempt의 존재와 요청 일치를 확인한다. 다른 제약 위반은 원래 오류로 전파한다. `payment.payment_key`는 승인 확정 시에만 저장하므로 TX A 실패로 롤백할 필드가 남지 않는다.
- Toss의 4xx 응답은 확정 실패로 분류해 attempt만 FAILED로 마킹한다. Payment는 PENDING을 유지해 같은 Order에 대한 새 결제 시도를 열어 둔다. 5xx, 타임아웃, 응답 유실처럼 승인 여부를 단정할 수 없는 오류는 attempt를 IN_PROGRESS로 유지하고 유저 재시도 시 게이트웨이 조회로 회복한다.
- Toss 성공 후 `PaymentApprovalFinalizer`가 새 트랜잭션에서 Payment를 다시 잠그고 승인 확정 시점에 `payment.payment_key`를 세팅하며 Order/Booking/Payment/Attempt/Outbox를 원자적으로 확정한다. 외부 호출 전에 읽은 엔티티는 확정에 재사용하지 않는다.

#### 사용자가 결제창에서 재시도했을 때 IN_PROGRESS attempt 정정

`PaymentApprovalStarter.recoverInProgressAttempt`는 사용자가 같은 결제창에서 다시 시도한 시점에 `PaymentGateway.query(paymentKey)`(Toss `GET /v1/payments/{paymentKey}`)로 실제 결제 상태를 확인한다.

| 게이트웨이 상태 | 처리 |
|----------------|------|
| `DONE` | TX B(재조회 결과)로 Order/Booking/Payment/Attempt/Outbox를 확정. Toss confirm은 재호출하지 않는다 |
| `ABORTED` · `EXPIRED` · `CANCELED` · `PARTIAL_CANCELED` | attempt를 FAILED로 마킹(`GATEWAY_<STATUS>`)하고 `PAYMENT_ATTEMPT_ALREADY_FAILED`로 응답 |
| `READY` · `IN_PROGRESS` · `WAITING_FOR_DEPOSIT` · 그 외 | 로컬 IN_PROGRESS 유지, `PAYMENT_ATTEMPT_IN_PROGRESS`로 응답. 재시도 안내 |

게이트웨이 조회 자체가 실패한 경우도 별도로 분기한다. 조회 API의 4xx는 승인의 4xx와 의미가 다르다.

| 조회 API 실패 | 처리 |
|--------------|------|
| `404 NOT_FOUND_PAYMENT` · `404 NOT_FOUND` | 해당 paymentKey에 대한 결제가 게이트웨이에 없음이 확정 → attempt를 FAILED로 마킹(`GATEWAY_NOT_FOUND_PAYMENT`/`GATEWAY_NOT_FOUND`) |
| `401 UNAUTHORIZED_KEY` · `403 INCORRECT_BASIC_AUTH_FORMAT` · `403 FORBIDDEN_CONSECUTIVE_REQUEST` · `500 FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING` · 기타 | 승인 여부 판단 불가 → 로컬 IN_PROGRESS 유지, `PAYMENT_ATTEMPT_IN_PROGRESS`로 응답 |

사용자가 재시도하지 않아도 오래 남은 IN_PROGRESS는 `PaymentRecoveryWorker`(#270)가 같은 조회 API로 대사한다. 사용자 재시도 정정 경로가 있어도 이 Worker는 사용자가 창을 닫아 재시도가 오지 않는 케이스와 크래시 회복을 커버한다.

### payment_outbox

DB 커밋 이후 Redis에 반영해야 할 작업을 기록한다. 결제 확정 트랜잭션 안에서 INSERT하므로 DB 상태 변경과 원자적으로 커밋된다.

| Column | Purpose |
|--------|---------|
| `id` | DB PK |
| `type` | `BOOKING_CONFIRMED` / `BOOKING_CANCELLED` |
| `aggregate_id` | 소비자 컨텍스트 (payment_id 등) |
| `deduplication_key` | 재발행 시 중복 처리 방지 (unique) |
| `payload` | Redis 정리에 필요한 최소 정보 (JSON) |
| `status` | `PENDING` / `DONE` / `FAILED` |
| `retry_count`, `next_retry_at` | Worker 재시도 상태 |
| `created_at`, `processed_at` | 감사 |

Payload는 Redis 정리 지점을 특정할 수 있는 최소 정보만 담는다. 예: `reservationIds`, `seatIds`, `trainCarId`, `stopOrders`. 취소는 부분 취소 대비 `cancelledSeatSections[]`까지 포함한다.

### OutboxWorker — 구현 완료 (#266)

`PaymentOutboxWorker`가 `@Scheduled` 폴링으로 동작한다. 다중 인스턴스 동시 실행에 대비해 `SELECT ... FOR UPDATE SKIP LOCKED`(Hibernate 6의 `jakarta.persistence.lock.timeout=-2`)로 처리 권한을 확보한다. 처리기는 `OutboxEventDispatcher`가 이벤트 타입에 따라 위임하며, `BOOKING_CONFIRMED`는 `BookingConfirmedProcessor`가 담당한다.

- 정상: Redis 정리 실행 → `status=DONE`
- 실패: `OutboxRetryPolicy`가 계산한 지수 backoff로 `retry_count++`, `next_retry_at` 갱신
- 최대 재시도 초과: `status=FAILED` 전환, `payment.outbox.failed` 카운터 증가
- **재시도 불가 실패: 백오프를 건너뛰고 즉시 `status=FAILED` 전환, `payment.outbox.failed`와 `payment.outbox.non_retryable` 카운터 증가**

재시도 불가 판정은 워커가 아니라 에러 코드가 한다. `ErrorCode.failedWorkRetryable()`의 기본값은 `true`이고, 재시도가 결과를 바꿀 수 없는 코드만 `false`로 선언한다. 현재 해당하는 코드는 `BookingError.SEAT_OCCUPANCY_CORRUPTED` 하나다. Redis에 이미 좌석 점유 값이 아닌 값이 들어 있는 상황이라 같은 스크립트를 몇 번 더 실행해도 같은 응답이 돌아온다.

판정을 에러 쪽에 둔 이유는 지식의 위치다. 재시도가 도움이 되는지를 아는 쪽은 에러를 던진 코드이고 워커가 아니다. 워커에 두면 재시도 큐가 늘어날 때마다 같은 분기를 다시 쓰게 된다. 레이어 규칙 때문은 아니다 — `payment.application`은 이미 `PaymentApprovalStarter`에서 `BookingError`를 import하고 있고 `PaymentHexagonalArchitectureTest`는 그걸 막지 않는다.

판정 대상은 특정 예외 클래스가 아니라 `ErrorCodeCarrier`를 구현한 예외다. 에러 코드를 싣는 예외가 `BusinessException`·`DomainException`·`RedisException` 셋이고 공통 부모가 `RuntimeException`뿐이라, 클래스를 나열하면 새로 생긴 예외가 조용히 빠진다. 에러 코드를 싣지 않은 실패(연결 끊김, 타임아웃)는 모두 재시도 대상으로 본다.

알림 대상 지표는 `payment_outbox_non_retryable_total`이다. 이 값이 0이 아니면 사람이 Redis 값을 치워야 하는 상황이며 재시도로 해결되지 않는다. **알림 규칙 등록은 후속 작업이다** — 현재 저장소에 Prometheus 알림 규칙이 없어서, 이 변경만으로는 FAILED에 도달하는 시점이 7.5분(30+60+120+240초) 빨라질 뿐 통보되지는 않는다. FAILED 행을 다시 투입하는 경로도 아직 없다. 쌓인 행 자체는 `payment_outbox_failed_rows` 게이지로 본다 — 카운터는 재시작하면 누적이 사라지기 때문이다.

`payment.outbox.non_retryable`은 `payment.outbox.failed`의 **부분집합**이다. 재시도 불가 한 건이 두 카운터를 모두 올린다. "재시도를 소진해서 실패한 건수"를 보려면 `failed - non_retryable`로 계산한다.

취소 이벤트(`BOOKING_CANCELLED`) 처리기와 발행은 이슈 #259가 담당한다.

### payload 스키마 변경 절차 (#302)

Outbox는 **쓴 코드와 읽는 코드가 다른 배포본일 수 있다.** 행이 테이블에 머무는 사이 애플리케이션이 재배포되기 때문이다. 그래서 payload는 `schemaVersion`을 들고 다니고, 소비자는 그 값이 지원 버전과 다르면 `PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED`로 거절한다. 버전 검사가 없으면 Jackson이 없는 필드를 기본값(`long`은 0, `List`는 null)으로 채워서, 존재하지 않는 예매 ID로 좌석을 건드리게 된다.

거절은 안전하지만 **구 버전 행을 처리할 방법은 아니다.** 그래서 비호환 변경을 할 때마다 테이블에 남아 있는 행을 어떻게 할지 먼저 정한다.

#### 측정된 이력 — 이 저장소에서 실제로 무슨 일이 있었나

`develop` push가 테스트를 통과하면 OKE 프로덕션에 배포된다(`.github/workflows/deploy_raillo_with_k8s.yml`은 `workflow_run`으로 "Build and Test with Gradle"의 `branches: [develop]` 성공에 걸려 있고 `group: raillo-oke-production`이다). 승인 게이트는 없다. 배포 실행 이력으로 확인한 시점:

| 배포 | 시각(UTC) | 생산자(#257) | payload v2(#266) |
|---|---|---|---|
| `f33d8a17` | 2026-09-21 08:52 | 있음 | **없음** |
| `4a0663de` | 2026-09-21 15:39 | 있음 | **없음** |
| `5c20daae` | 2026-09-22 03:31 | 있음 | 있음 |
| 이후 전부 | | 있음 | 있음 |

**프로덕션이 구 모양 payload를 쓸 수 있었던 창은 약 19시간이다**(09-21 08:52 ~ 09-22 03:31 UTC). #257의 develop 머지일(2026-09-13, 머지 커밋 `4166620f`)과 혼동하지 않는다 — 머지와 배포가 8일 떨어져 있었다. 커밋 날짜(2026-09-11)는 머지일이 아니다. 09-21 이전에는 `develop`을 배포하는 파이프라인 자체가 없었다(그때까지는 `main` push → AWS EKS였고, `bd452ca1`이 OKE·develop로 바꿨다).

그리고 **배포된 모든 SHA에서 `BookingConfirmedProcessor`에 `@Component`가 없었다.** 소비자가 프로덕션에서 한 번도 돌지 않았으므로 그 사이 쓰인 행은 전부 PENDING으로 남아 있을 것이다. 이 브랜치가 `@Component`를 붙여 워커가 처음으로 그 행들을 집게 된다.

운영에서 결제까지 테스트한 적이 없다는 것이 작업자 확인 사항이다. 그렇다면 행이 없을 가능성이 높다. **DB를 직접 확인하지 않았으므로 단정하지 않는다.**

#### 지금 상태를 확인하는 쿼리

MySQL 8.4이고 `payload`는 TEXT다. 아래 쿼리는 `mysql:8.4`(`ONLY_FULL_GROUP_BY` 기본값) 컨테이너에 7행 픽스처를 넣고 실행 확인했다.

**`LIKE '%"schemaVersion":2%'`로는 안 된다.** 실행으로 확인한 결함 넷:

| 행 | 실제 | `NOT LIKE` 판정 |
|---|---|---|
| `{ "schemaVersion" : 2 , ...}` | v2 | **구버전으로 오분류** — 콜론 뒤 공백 하나에 깨진다 |
| `{"schemaVersion":21,...}` | v21 | **v2로 오분류** — `":2"`가 부분 문자열로 들어 있다. 2로 시작하는 모든 버전이 v2로 보인다 |
| `{"schemaVersion":2,"paymentId":401` (깨짐) | 파싱 불가 | **v2로 오분류** — 깨진 앞부분에 `":2"`가 남아 있어 아예 안 보인다 |
| `{"schemaVersion":2}` (`bookings` 없음) | 처리 불가 | **v2로 오분류** — 버전만 보는 조건으로는 원리상 못 잡는다 |

두 번째가 가장 위험하다. 조용히 틀리고, 버전이 10을 넘기면 드러난다.

**`JSON_VALID` 가드는 선택이 아니다.** 가드 없이 `payload ->> '$.schemaVersion'`을 쓰면 깨진 행에서 `ERROR 3141`로 쿼리 전체가 죽는다(NULL이 아니다). `CASE`는 지연 평가되므로 `NOT JSON_VALID(payload)`를 첫 `WHEN`에 두면 뒤 분기가 깨진 행을 보지 않는다. `WHERE`에서도 같은 `CASE`로 감싼다 — `OR`의 평가 순서는 보장되지 않는다.

**분포 — 무엇이 몇 개 있나**

```sql
SELECT CASE
         WHEN NOT JSON_VALID(payload)                     THEN 'invalid-json'
         ELSE COALESCE(payload ->> '$.schemaVersion', 'no-schemaVersion-key')
       END AS schema_version,
       CASE
         WHEN NOT JSON_VALID(payload)                     THEN '-'
         WHEN JSON_EXTRACT(payload, '$.bookings') IS NULL THEN 'bookings-missing'
         ELSE 'ok'
       END AS bookings_check,
       status,
       count(*)        AS row_count,
       min(created_at) AS first_seen,
       max(created_at) AS last_seen
  FROM payment_outbox
 WHERE type = 'BOOKING_CONFIRMED'
 GROUP BY schema_version, bookings_check, status
 ORDER BY CAST(NULLIF(schema_version, 'invalid-json') AS UNSIGNED),
          schema_version, bookings_check, status;
```

`parse()`가 한 에러 코드로 덮는 세 조건이 결과표에서 분리돼 보인다 — 깨진 JSON은 `schema_version = 'invalid-json'`, 레거시 행은 `'no-schemaVersion-key'`, `bookings` 누락은 `bookings_check = 'bookings-missing'`이다. 적어도 조회 단계에서는 "에러 코드 하나가 세 사고를 덮는" 문제가 풀린다.

고칠 때 걸리는 지점 넷. 전부 실행으로 확인했다.

- `count(*) AS rows`는 **문법 오류**다. `ROWS`는 MySQL 8.0부터 윈도 함수 예약어다.
- `bookings_check`를 `SELECT`에만 넣고 `GROUP BY`에서 빼면 `ONLY_FULL_GROUP_BY`가 `ERROR 1055`로 거절한다.
- `->>`(`JSON_UNQUOTE(JSON_EXTRACT(...))`)로 문자열로 통일한다. `JSON_EXTRACT`는 JSON 타입을 돌려주므로 문자열 리터럴과 한 `CASE`에 섞이면 강제 변환되고 표기가 환경에 따라 달라진다.
- `->>`가 문자열이므로 **정렬도 문자열이다.** `ORDER BY schema_version`만 쓰면 `21`이 `3` 앞에 온다. `CAST(... AS UNSIGNED)`로 숫자 정렬하고, 숫자가 아닌 `'invalid-json'`은 `NULLIF`로 빼서 `CAST` 경고를 피한다.

**조치용 행 목록 — 무엇을 어떻게 할지 정하려면 ID가 필요하다**

```sql
SELECT payment_outbox_id, aggregate_id, status, retry_count, created_at,
       CASE
         WHEN NOT JSON_VALID(payload)                     THEN 'a:invalid-json'
         WHEN payload ->> '$.schemaVersion' IS NULL       THEN 'b:no-schemaVersion-key'
         WHEN payload ->> '$.schemaVersion' <> '2'        THEN 'b:unsupported-version'
         WHEN JSON_EXTRACT(payload, '$.bookings') IS NULL THEN 'c:bookings-missing'
       END AS reason
  FROM payment_outbox
 WHERE type = 'BOOKING_CONFIRMED' AND status = 'PENDING'
   AND CASE
         WHEN NOT JSON_VALID(payload)                     THEN TRUE
         WHEN payload ->> '$.schemaVersion' IS NULL       THEN TRUE
         WHEN payload ->> '$.schemaVersion' <> '2'        THEN TRUE
         WHEN JSON_EXTRACT(payload, '$.bookings') IS NULL THEN TRUE
         ELSE FALSE
       END
 ORDER BY created_at;
```

`CASE`의 `WHEN`은 위에서 처음 맞는 하나만 쓰이므로 사유가 하나로 확정된다 — 깨진 행은 뒤 조건을 아예 따지지 않는다. `WHERE`의 `CASE`는 `SELECT`의 것과 같은 순서·같은 조건이어야 한다. **어긋나면 `reason`이 `NULL`인 행이 나오고, 그게 두 곳이 안 맞는다는 신호다.**

`schema_version`이 `2`가 아니거나 `bookings_check`가 `ok`가 아닌 행이 0이면 할 일이 없다. 0이 아니면 아래 절차를 적용한다.

#### 앞으로 payload를 바꿀 때

1. **호환 변경인지 먼저 판정한다.** 선택적 필드 추가처럼 구 JSON으로도 소비자가 정상 동작하면 버전을 올리지 않는다. 기존 필드의 의미·타입·필수 여부가 바뀌거나 필드가 사라지면 올린다.

2. **비호환이면 구 행을 payload만으로 복원할 수 있는지 본다.** v1→v2가 복원 불가였던 예다 — v1 `Entry`에는 예매 ID(`bookingId`)·객차 ID·운행일이 없고 구간은 stop PK(`departureStopId`/`arrivalStopId`)로만 있어서, DB 조회 없이는 새 모양을 만들 수 없었다. 소비자가 키로 쓰는 `bookingId`가 없는 것이 결정적이다(v1의 `pendingBookingId`는 Redis ID다).

   복원 가능하면 소비자가 양쪽 버전을 읽게 만든다 — `parse()`의 `event.schemaVersion() != SUPPORTED_SCHEMA_VERSION` 단일 상수 비교를 집합 포함 검사로 바꾸는 일이다. 다만 **생산자와 소비자가 같은 배포 단위다**(`PaymentApprovalFinalizer`, `BookingConfirmedProcessor`, `PaymentOutboxScheduler`가 모두 `raillo-api`). 그래서 배포 순서가 아니라 **릴리스 순서**다 — 릴리스 N에서 소비자가 양쪽을 읽게 하고, 릴리스 N+1에서 생산자를 바꾼다. 배포 순서로 처리할 수 있게 되는 건 #277로 워커를 분리한 뒤다.

3. **복원 불가면 배포 전에 적체를 비운다.** 소비자가 돌고 있으면 드레인을 기다린 뒤 생산자를 바꾼다. 소비자가 안 돌고 있으면(이 저장소는 이 브랜치 배포 전까지 그랬다) 위 쿼리로 세고 변환·폐기·FAILED 마킹 중 하나를 선택한다.

   **엄격 거절은 테이블에 지원하지 않는 버전의 행이 없을 때만 안전하다.** 단일 버전만 받는 `parse()`로는 어느 순서로 배포해도 깔끔한 전환이 안 된다. 그리고 이 배포는 `replicas: 1` · `strategy: Recreate`라 **두 버전이 동시에 돌지 않는다** — 롤링 엇갈림이 재시도로 치유되는 시나리오 자체가 없고, 재시도 가능 분류는 FAILED 도달을 7.5분 늦추며 디스패치 5회와 `payment.cleanup.failure` 증가를 소비할 뿐이다. **그대로 두면 구 행은 7.5분 백오프를 전부 태우고 FAILED로 간다.** N개여도 전체가 약 7.5분 안에 끝난다 — `batch-size: 50`이라 한 번의 poll이 최대 50행을 잠그고 루프를 돌아 각 행의 백오프가 **동시에** 진행되며, `lockProcessable`이 `next_retry_at asc nulls first`로 정렬해 아직 시도되지 않은 행이 앞에 온다. N에 비례하는 것은 시간이 아니라 디스패치 시도 5N회와 ERROR 로그·`payment.outbox.failed` 증가분 N이다. 그리고 그 FAILED에는 원인이 남지 않는다(다음 절).

4. **생산자만 배포된 상태를 길게 두지 않는다.** 소비자가 빈이 아닌 동안 쌓인 행은 그 기간의 계약으로 고정되고, 그 뒤 계약이 바뀌면 전부 3번의 대상이 된다. 이 저장소가 정확히 그 상태였다 — 생산자는 2026-09-21부터, 소비자는 이 브랜치까지 없었다.

### FAILED 행에 실패 원인이 남지 않는다 (#302)

`payment_outbox`에는 **실패 원인을 담는 컬럼이 없다.** 엔티티가 매핑하는 컬럼은 PK `payment_outbox_id`와 `type`, `aggregate_id`, `deduplication_key`, `payload`, `status`, `retry_count`, `next_retry_at`, `created_at`, `processed_at`뿐이다(저장소에 DDL이 없고 prod는 `ddl-auto: validate`라, 매핑되지 않은 컬럼이 실제 스키마에 있는지는 확인하지 않았다). 그래서 FAILED 행을 보고 알 수 있는 것은 "실패했다"와 "몇 번 시도했다"까지이고, **왜 실패했는지는 알 수 없다.**

원인은 **집계로만** 남는다. 총량은 `payment.outbox.non_retryable`로 오염과 재시도 소진이 갈리고, 행별 원인은 로그에만 있다(`[Outbox 처리 실패]`, `[Outbox 재시도 불가 - 즉시 FAILED]`, `[Outbox 최대 재시도 초과]`). 로그는 보존 기간이 지나면 사라지고, 행은 남는다.

재투입 전에 사람이 원인을 확인해야 한다는 점에서 이건 애플리케이션 재투입 경로보다 먼저 필요하다(수동 SQL 재투입은 `docs/reservation-cache-schema.md` 2장에 있다). 원인을 모르면 재투입이 같은 실패를 반복할 뿐이다.

구분이 되는 부분과 안 되는 부분을 나눠 보면:

| | 식별 수단 |
|---|---|
| 구 payload 행 | `payload`로 직접 식별된다 — 위 쿼리가 그 용도다 |
| 즉시 FAILED된 오염 행 | `retry_count = 0`. 재시도를 소진한 FAILED는 `retry_count = 4`다(`markFailed()`는 증가시키지 않는다). `processed_at - created_at`도 ~0 대 ~7.5분으로 갈린다 |
| **재시도를 소진한 FAILED 안에서** 충돌·타임아웃·스크립트 오류·오염 | **가려낼 수단이 없다.** 두 원인이 섞인 경우도 구분되지 않는다 |

마지막 줄이 실제 공백이다. 대응이 정반대인데(구 payload는 변환이나 폐기, 좌석 오염은 Redis 값 정리) 행에 남는 것은 "재시도를 다 썼다"까지다.

### PaymentRecoveryWorker — 후속 브랜치·이슈

도입 후에는 `IN_PROGRESS` 상태이면서 `updated_at`이 임계값을 넘긴 `payment_attempt`를 조회한다. Toss 결과 조회 API로 실제 상태를 확인한 뒤 다음 중 하나로 확정한다. 현재 브랜치에서는 이 자동 복구가 실행되지 않는다.

| Toss 상태 | 처리 |
|----------|------|
| 성공 + 우리 DB 미확정 | **롤포워드**: 결제 확정 트랜잭션 재실행 + outbox INSERT |
| 실패 | **보상**: `payment_attempt.status=FAILED`, `Payment.fail()` |
| 미확정 | 다음 폴링까지 대기 |

## Transaction Boundary

```
[트랜잭션 A — Toss 호출 전]
  Payment SELECT FOR UPDATE
  payment_attempt INSERT (IN_PROGRESS, payment_key 사전 저장)
  COMMIT

[트랜잭션 밖]
  Toss 호출 (외부 I/O)
  — IN_PROGRESS 재요청 시엔 confirm 대신 PaymentGateway.query로 상태 재조회

[트랜잭션 B — 승인 확정]
  Payment SELECT FOR UPDATE + 최신 상태 재검증
  payment.payment_key 세팅 (승인 확정 시점)
  Order / Payment / Booking 상태 변경
  payment_attempt.status = SUCCEEDED
  payment_outbox INSERT
  COMMIT

[별도 스케줄러 — 구현 완료 #266]
  PaymentOutboxWorker가 PENDING outbox 행 폴링 → Redis 정리 → DONE / 재시도 / FAILED

[후속 구현 — Recovery Worker #270]
  IN_PROGRESS attempt의 Toss 상태 조회 및 복구
```

승인 자체의 원자성(Payment/Order/Booking)은 트랜잭션 B가 보장한다. Redis 정리는 이제 승인 트랜잭션 밖에서 Worker가 비동기로 수행하므로 승인 API 응답에 영향을 주지 않으며, 정리 실패 시 지수 backoff로 자동 재시도된다.

`PaymentConfirmService.confirm()`은 자체 트랜잭션을 열지 않고 위 세 단계를 순서대로 연결하기만 한다.

dev·prod·test 모두 `spring.jpa.open-in-view=false`로 설정한다. HTTP 요청 전체에 영속성 컨텍스트를 유지하지 않으므로, 승인 시작 단계에서 조회한 엔티티를 TX B의 영속성 컨텍스트에서 재사용하지 않는다. 이 경계는 상위 호출자도 트랜잭션을 열지 않는 현재 승인 API 경로를 전제로 한다.

| 단계 | 담당 | 트랜잭션 |
|---|---|---|
| 승인 시작 | `PaymentApprovalStarter.start` | 조회는 각 컴포넌트의 짧은 트랜잭션, 커밋은 아래 한 곳뿐 |
| └ 트랜잭션 A |  `PaymentAttemptManager.startApprovalInNewTransaction` | `REQUIRES_NEW` |
| Toss 승인 요청 | `PaymentGateway.confirm` | 없음 |
| 트랜잭션 B | `PaymentApprovalFinalizer.finalizeApproval` | `REQUIRED` |

### 3층 재검증 구조

같은 검증(`validateApprovable`·`validateDuplicatePayment`·`validateApprovalAttempt`·`validateAmounts`)이 세 지점에서 반복 실행된다. 낭비가 아니라 각 층이 다른 시점의 상태를 다시 확인해 동시성·응답 대기 사이 상태 변화를 잡는 방어층이다.

| 층 | 위치 | 시점 | 목적 |
|---|---|---|---|
| pre-check | `PaymentApprovalStarter.start` | 트랜잭션 없음 | 잠금 없이 빠른 fail. Toss 호출까지 가지 않고 조기 종료. |
| TX A | `PaymentAttemptManager.startApprovalInNewTransaction` | `SELECT FOR UPDATE`로 Payment 잠금 획득 후 | pre-check와 잠금 획득 사이에 다른 요청이 attempt를 등록했거나 Payment 상태를 바꿨을 가능성 감지. |
| TX B | `PaymentApprovalFinalizer.finalizeApproval` | Toss 호출 완료 후 Payment 재잠금 | Toss 응답 대기 동안 다른 트랜잭션(예: 동시에 처리된 다른 attempt가 승인 확정)이 상태를 바꿨을 가능성 감지. |

이 구조 덕에 두 요청이 거의 동시에 들어와도 늦게 잠금을 잡은 요청이 `PAYMENT_ALREADY_COMPLETED`로 저지되고, Toss로 두 번째 승인 호출이 나가 이중 청구가 발생하지 않는다. 관련 케이스: [케이스 13(새 세션 IN_PROGRESS), 케이스 14(SUCCEEDED race)](./payment-cases.md).

### 동시 진입 방어 매커니즘

3층 재검증이 "언제 무엇을 확인하나"라면, 아래는 "재확인에서 늦게 도착한 요청을 어떻게 안전하게 종결하나"의 매커니즘이다. 핵심은 TX B의 Payment pessimistic lock으로 동시 진입을 직렬화한 뒤 attempt status로 뒤늦은 진입을 무해 처리하는 것이다.

| 방어 지점 | 위치 | 동작 |
|---|---|---|
| TX B 락 직렬화 | `PaymentApprovalFinalizer.finalizeApproval` | `paymentRepository.findByIdForUpdate(paymentId)`로 Payment에 pessimistic lock을 잡는다. 같은 Payment에 대한 두 TX B 진입은 DB가 순차 처리한다. |
| Finalizer 이중 진입 조기 리턴 | `PaymentApprovalFinalizer.finalizeApproval` | 락 획득 후 `attempt.status == SUCCEEDED`이면 이전 결과를 그대로 반환한다. 원본과 재시도가 둘 다 DONE 경로로 갔을 때 뒤늦게 락을 얻은 쪽이 커밋하지 않는다. |
| markFailed idempotency | `PaymentAttemptManager.markFailedInNewTransaction` | attempt가 이미 종결(SUCCEEDED/FAILED)이면 no-op으로 종료한다. 원본과 재시도가 둘 다 markFailed로 갈 때 뒤늦은 호출을 무해하게 종결한다. |
| 도메인 상태 전이 검증 | `PaymentAttempt.markSucceeded` · `markFailed` | status가 IN_PROGRESS가 아니면 `DomainException(PAYMENT_ATTEMPT_NOT_TRANSITIONABLE)`. 위 방어를 모두 우회하는 경로에서 최후 방어선이다. |

이 매커니즘이 겹쳐 있어 다음 시나리오가 데이터 오염 없이 종결된다.

- **원본 confirm 대기 중 유저가 재시도, 둘 다 DONE 확인** ([케이스 15](./payment-cases.md)): 두 스레드가 TX B에서 락 경합. 먼저 획득한 쪽이 확정하고, 뒤 쪽은 attempt.status == SUCCEEDED로 조기 리턴한다.
- **원본은 4xx 실패, 재시도가 먼저 ABORTED로 markFailed** ([케이스 16](./payment-cases.md)): 원본의 뒤늦은 markFailed는 idempotency로 no-op 종료.
- **원본이 5xx, 재시도가 DONE** ([케이스 4](./payment-cases.md)): 5xx는 attempt를 IN_PROGRESS로 남기므로 재시도가 정상 확정 경로로 진행한다.

## Failure Coverage — 후속 작업 완료 후 목표

| 실패 지점 | 대응 |
|----------|------|
| Toss 5xx·타임아웃·응답 유실 (유저 재요청) | `PaymentApprovalStarter.recoverInProgressAttempt`가 `PaymentGateway.query`로 Toss 상태 재조회 후 로컬 정정 |
| Toss 승인 후 DB 커밋 전 크래시 (유저 재요청 없음) | `PaymentRecoveryWorker`가 `IN_PROGRESS` attempt를 Toss 조회로 확정 |
| DB 커밋 후 Redis 정리 실패 | `OutboxWorker`가 `PENDING` outbox 행 재시도 |
| Redis 정리 중 일시 오류 | outbox 재시도, `retry_count` 초과 시 알람 |
| Toss 취소 후 DB 커밋 전 크래시 | Recovery Worker가 취소 attempt 확정 |
| DB 취소 커밋 후 좌석 해제 실패 | outbox 재시도로 좌석 해제 최종 반영 |

## Metrics — 후속 이슈에서 도입

| Name | Type | Purpose |
|------|------|---------|
| `payment.attempt.in_progress` | Gauge | 진행 중 attempt 수 |
| `payment.attempt.recovered` | Counter | Recovery Worker가 복구한 건수 |
| `payment.outbox.pending` | Gauge | 미처리 outbox 행 수 |
| `payment.outbox.failed` | Counter | FAILED 전이 건수 (최대 재시도 초과 + 재시도 불가) |
| `payment.outbox.non_retryable` | Counter | 재시도 불가로 백오프 없이 FAILED 전이된 건수. `failed`의 부분집합 |
| `payment.outbox.failed_rows` | Gauge | FAILED로 종착해 사람 개입을 기다리는 행 수. 카운터와 달리 재시작에도 남는다 |
| `payment.cleanup.failure` | Counter | Redis 정리 예외 발생 |

## Alternatives

| 방식 | Scenario 1 | Scenario 2 | 프로세스 크래시 회복 | 관측/재시도 |
|------|-----------|-----------|-------------------|-----------|
| 개선 전 (`@Transactional`에 정리 포함) | 미커버 | 미커버 (롤백 위험) | 불가 | 없음 |
| `AFTER_COMMIT` 이벤트 | 미커버 | 커버 | 불가 (이벤트 유실) | 리스너 로그만 |
| PaymentAttempt + Outbox + Workers (목표) | 커버 | 커버 | 가능 | 지표와 재시도 이력 |

## Rollout

### 후속 브랜치·이슈 분리 (2026-09-13)

기존 Tasks 8–12를 하나의 Worker PR로 묶는 계획에서 다음 두 범위로 나눈다. 이 문서의 후속 항목은 현재 브랜치에서 구현하지 않는다.

- **Recovery Worker — 다음 브랜치·이슈:** 네트워크 타임아웃·응답 유실, Toss 성공 후 DB 확정/커밋 실패로 남은 `IN_PROGRESS`를 Toss 조회로 대사한다. 결과가 미확정이면 실패로 단정하거나 승인 API를 재호출하지 않고 다음 폴링까지 유지한다. 처리 권한 확보, 승인 확정과의 경합, 복구 지표 및 복구 후 같은 attemptId 재요청을 검증한다.
- **Outbox — #266에서 인프라, #270에서 R→B 처리기 완료.** #266은 승인 확정 시 outbox INSERT와 `PaymentOutboxWorker`의 폴링, 재시도, 최대 시도 초과 정책을 도입하고 인라인 정리 코드를 제거했다. `BookingConfirmedProcessor`는 #266 시점에는 등록되지 않아 `BOOKING_CONFIRMED`가 PENDING으로 쌓였고, #270에서 구현해 등록했다. 처리 규칙은 `docs/reservation-cache-schema.md` 2장에 있다.

승인 재요청의 요청 일치 검증, 최신 결과 조회, 결제별 동시 승인 차단, 승인 가능 상태 검증,
입력 검증과 DB 무결성 오류 구분은 Worker 도입으로 해결되지 않으므로 선행 수정한다.

### 적용 순서

1. 승인 흐름에 `payment_attempt`와 `payment_outbox` 도입 (이슈 #257)
2. 다음 브랜치·이슈에서 `PaymentRecoveryWorker` 신설
3. 별도 Outbox 이슈에서 기존 저장 구현을 기반으로 `OutboxWorker`와 Redis 정리 처리기 구현
4. 취소 흐름에 대칭 적용 (이슈 #259)
5. 실제 Redis 정리 처리기 연결 후 기존 인라인 cleanup 제거
6. 각 Worker 이슈에서 관측 지표와 알람 추가

## Future Extensions

각 항목은 필요 시점에 별도 이슈로 다룬다.

### 클라이언트 Idempotency-Key 헤더

`payment_attempt`는 서버 재시도의 멱등성을 다룬다. 클라이언트가 결제 버튼을 두 번 누르는 케이스는 API 진입 지점에서 `Idempotency-Key` 헤더를 Redis에 캐시하는 별도 층으로 처리한다. (이슈 #260)

### Toss 웹훅 리스너

Toss는 승인과 취소 결과를 API 응답과 웹훅으로 이중 통지한다. 목표 설계는 API 응답과 후속 Recovery Worker의 폴링을 사용한다. 웹훅을 세 번째 대사 채널로 추가하면 응답 유실 시 회복 지연을 줄일 수 있다. 현재 브랜치에는 폴링과 웹훅 대사가 모두 없다.

### Redis Streams 기반 알림 fan-out

알림 채널이 여러 개로 확장되면 `payment_outbox` 발행 채널을 Redis Streams로 확장한다. Outbox INSERT는 그대로 유지하고 Publisher가 로컬 소비자와 Redis Streams topic에 함께 발행한다. 채널이 하나뿐이면 도입하지 않는다.

### 시간 기반 알림 (출발 전 알림 등)

"출발 30분 전" 같은 예약 기반 발송은 이벤트 스트림과 성격이 다르다. 스케줄 outbox 테이블 또는 Redis ZSET(score = 발송 시각) 기반 Worker로 별도 처리한다.

### MSA 분리 시점의 브로커 재검토

Auth와 Payment 등이 별도 서비스로 분리되면 서비스 경계를 넘는 이벤트가 필요해진다. 이 시점에 Kafka(자체 호스팅 또는 OCI Streaming) 도입을 재검토한다. `Outbox → Kafka Publisher` 패턴으로 확장하면 기존 코드 구조 변경이 최소화된다.

## Out of Scope

- 결제 승인과 취소 자체의 비즈니스 규칙 (환불 조건, 금액 계산 등)
- 좌석 충돌 검증 4-Layer 방어 (`docs/seat-conflict-validation.md`)
- Reservation 만료 처리 방식 개편
