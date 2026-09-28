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

현재 승인 API 요청 body에는 `attemptId` 필드가 없고, 서버가 항상 `SHA-256("apv:" + paymentKey)`의
64자리 16진수 문자열을 파생해 쓴다(`PaymentAttemptIds`, `PaymentConfirmCommand.attemptId()`). Idempotency-Key 헤더 연동은 #260 범위다.

#### 승인 재요청과 동시 실행 방어

- 같은 attemptId는 `paymentId`, `paymentKey`, `APPROVAL` 타입까지 일치해야 재사용할 수 있다. 불일치하면 `PAYMENT_114`, 64자를 초과한 입력은 API 검증 또는 애플리케이션의 `PAYMENT_115`로 거절한다.
- 성공한 시도는 DB에서 승인 결과 DTO를 직접 조회한다. 이미 로딩한 Payment 엔티티를 그대로 반환하지 않으므로, 다른 트랜잭션이 방금 승인한 결과도 반영한다. 최초 응답을 저장·재생하는 방식은 아니며 환불 등 이후 상태 변경도 반영한다.
- `PaymentAttemptManager.startApprovalInNewTransaction`은 짧은 `REQUIRES_NEW` 트랜잭션에서 Payment 행을 잠그고, 기존 승인 시도와 승인 가능 상태를 확인한 뒤 attempt를 `IN_PROGRESS`로 INSERT한다. `payment.payment_key`는 이 시점에 세팅하지 않는다. 잠금과 DB 트랜잭션은 Toss 호출 전에 해제한다.
- 반환값 `PaymentAttemptStartResult.created`가 true인 호출만 승인 API를 실행한다. 동일 시도의 재사용은 false로 반환한다. 다른 attemptId를 보내도 진행 중(`IN_PROGRESS`)이거나 확인 필요(`REVIEW_REQUIRED`)인 기존 승인은 우회할 수 없다. 실패(`FAILED`)한 승인 뒤에는 새 attemptId로 다시 시도할 수 있다(다른 카드 재결제).
- Payment 자체가 FAILED/CANCELLED/REFUNDED로 끝났다면 새 주문과 결제를 준비한다. 그 Payment로 들어온 승인 요청은 외부 호출 전에 거절한다.
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

재시도 불가 판정은 워커가 아니라 에러 코드가 한다. `ErrorCode.retryable()`의 기본값은 `true`이고, 재시도가 결과를 바꿀 수 없는 코드만 `false`로 선언한다. 현재 해당하는 코드는 `BookingError.SEAT_OCCUPANCY_CORRUPTED` 하나다. Redis에 이미 좌석 점유 값이 아닌 값이 들어 있는 상황이라 같은 스크립트를 몇 번 더 실행해도 같은 응답이 돌아온다.

판정을 에러 쪽에 둔 이유는 지식의 위치다. 재시도가 도움이 되는지를 아는 쪽은 에러를 던진 코드이고 워커가 아니다. 워커에 두면 재시도 큐가 늘어날 때마다 같은 분기를 다시 쓰게 된다. 레이어 규칙 때문은 아니다 — `payment.application`은 이미 `PaymentApprovalStarter`에서 `BookingError`를 import하고 있고 `PaymentHexagonalArchitectureTest`는 그걸 막지 않는다.

판정 대상은 특정 예외 클래스가 아니라 `ErrorCodeCarrier`를 구현한 예외다. 에러 코드를 싣는 예외가 `BusinessException`·`DomainException`·`RedisException` 셋이고 공통 부모가 `RuntimeException`뿐이라, 클래스를 나열하면 새로 생긴 예외가 조용히 빠진다. 에러 코드를 싣지 않은 실패(연결 끊김, 타임아웃)는 모두 재시도 대상으로 본다.

알림 대상 지표는 `payment_outbox_non_retryable_total`이다. 이 값이 0이 아니면 사람이 Redis 값을 치워야 하는 상황이며 재시도로 해결되지 않는다. **알림 규칙 등록은 후속 작업이다** — 현재 저장소에 Prometheus 알림 규칙이 없어서, 이 변경만으로는 FAILED에 도달하는 시점이 15분 빨라질 뿐 통보되지는 않는다. FAILED 행을 다시 투입하는 경로도 아직 없다.

`payment.outbox.non_retryable`은 `payment.outbox.failed`의 **부분집합**이다. 재시도 불가 한 건이 두 카운터를 모두 올린다. "재시도를 소진해서 실패한 건수"를 보려면 `failed - non_retryable`로 계산한다.

취소 이벤트(`BOOKING_CANCELLED`) 처리기와 발행은 이슈 #259가 담당한다.

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
| markFailed 잠금과 idempotency | `PaymentAttemptManager.markFailedInNewTransaction`, `markReviewRequiredInNewTransaction` | TX B와 같은 Payment pessimistic lock을 먼저 잡은 뒤 attempt를 처음 읽는다. 잠금 전에 읽으면 그 시점 스냅샷으로 판단해 먼저 커밋된 SUCCEEDED를 FAILED로 덮어쓸 수 있다(#292). attempt가 이미 종결(SUCCEEDED, FAILED, REVIEW_REQUIRED)이면 no-op으로 종료한다. |
| 도메인 상태 전이 검증 | `PaymentAttempt.markSucceeded`, `markFailed`, `markReviewRequired` | status가 IN_PROGRESS가 아니면 `DomainException(PAYMENT_ATTEMPT_NOT_TRANSITIONABLE)`. 위 방어를 모두 우회하는 경로에서 최후 방어선이다. |

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

## 결제 중 좌석 보호 — 방침 미확정

문서 세 개가 서로 다른 말을 하고 있어 정리가 필요하다.

| 문서 | 서술 |
|---|---|
| `payment-reservation-revised-plan.md` 5줄 | "결제 중 좌석 보호 전제를 폐기하고, 예약 TTL만 신뢰하는 방향으로 재작업한다" |
| `reservation-cache-schema.md` 46줄 | "#270 후속 작업의 결제 중 좌석 보호로 자기 field가 먼저 사라지지 않게 한다" |
| `payment-reservation-work-roadmap.md` §8 | 방향 A(HPERSIST로 attempt 완료까지 만료 방지)를 #270 착수 시 결정할 후보로 남겨둠 |

브랜치 이름도 `feature/270-seat-protection-worker`라 폐기 이후 다시 검토한 흔적이 있다.

### 왜 정해야 하나

예약 TTL이 10분인데, 확정을 늦게 하는 경로들이 그 시간을 넘긴다.

- Recovery Worker는 정의상 오래 남은 `IN_PROGRESS`를 처리한다. 그 기준이 10분을 넘으면 좌석은 이미 풀려 있다.
- Webhook은 사용자가 결제창을 닫은 뒤 도착하므로 시간이 지나 있을 수 있다.
- Outbox 재시도 창은 약 7.5분으로 TTL보다 짧지만, Worker가 지연되면 걸친다.

즉 Recovery Worker를 도입하면 `REVIEW_SEAT_LOST`가 구조적으로 발생한다. 드문 예외가 아니라 늦게 확정하는 경로를 만들면 따라오는 결과다.

### 카드 거절 후 재결제와 TTL

늦게 확정하는 경로 말고 재결제도 TTL을 소비한다. 잔액 부족이나 한도 초과로 승인이 거절되면 사용자는 다른 카드로 다시 결제해야 하고, 그동안 좌석이 유지되어야 한다. 흐름 자체는 [케이스 2](./payment-cases.md)와 `payment-reservation-revised-plan.md` 31~36줄에 이미 있으므로 여기서는 TTL 논점만 다룬다.

재결제가 새 결제창 경로가 되는 근거는 우리 코드 안에 있다. 같은 `paymentKey`로 다시 들어오면 `attemptId`가 같으므로 `PaymentApprovalStarter.handleExistingAttempt`의 `case FAILED`가 `PAYMENT_ATTEMPT_ALREADY_FAILED`를 던진다. 게이트웨이 동작과 무관하게 새 `paymentKey`를 받아야 통과한다.

| 단계 | 결과 |
|---|---|
| 결제창 → `paymentKey` A → confirm 4xx | `PaymentConfirmService`가 attempt A를 `FAILED`로 기록. `Payment`는 `PENDING` 유지 |
| 같은 `paymentKey`로 재요청 | `attemptId`가 같아 `PAYMENT_ATTEMPT_ALREADY_FAILED`로 거절 |
| 결제창 재오픈 → 새 `paymentKey` B | 새 `attemptId`(`SHA-256("apv:" + paymentKey)`) |
| TX A의 직전 attempt 검사 | `case FAILED -> { /* 재시도 허용 (다른 카드) */ }`로 통과 |

정상 경로에는 추가 구현이 없고 제약은 남은 예약 TTL뿐이다. 사용자가 카드를 바꿔 다시 결제하는 시간이 거기 들어가야 하므로, TTL 연장 여부를 정할 때 Recovery Worker나 Webhook뿐 아니라 이 경로도 함께 본다.

다만 예외가 하나 있다. 4xx 뒤 `markFailedInNewTransaction`이 Payment 잠금 대기 등으로 실패하면 로그만 남고 attempt는 `IN_PROGRESS`로 남는다(`PaymentConfirmService` 50~61줄). 이 상태에서 다른 카드로 재시도하면 `case IN_PROGRESS -> PAYMENT_ATTEMPT_IN_PROGRESS`에 막힌다. 코드 주석은 Recovery Worker가 대사하는 것으로 넘기지만 그 Worker가 이 섹션이 다루는 후속 작업이라, 현재는 회복 주체가 없다. 재결제 가능 조건이 TTL 하나만은 아니라는 뜻이다.

`payment-reservation-revised-plan.md` 16줄과 18줄은 **같은 재결제 시나리오를 근거로** "예약 TTL 하나로 충분하고 결제 시점 별도 보호는 UX 저하만 만든다"고 결론 낸다. 이 섹션은 같은 시나리오를 TTL 연장 검토 근거로 쓰므로, 입장 차이를 인지한 상태에서 정해야 한다.

### 선택지

| 안 | 내용 | 비용 |
|---|---|---|
| TTL만 신뢰 (현재 방침) | 만료되면 `REVIEW_REQUIRED`로 분리 | 운영자 확인 건수가 쌓인다. 규모는 Worker 폴링 주기에 달렸다 |
| 결제 시작 시 TTL 연장 (폐기된 방향 A) | attempt가 끝날 때까지 좌석 보호 | 예약 정리 책임이 attempt 상태 전이에 붙어 코드 경로가 늘어난다 |
| 정해진 만큼만 연장 | 결제 시작 시 TTL을 한 번 연장하고 무한 보호는 하지 않는다 | 연장 폭과 만료 후 처리를 따로 정해야 한다 |

### 먼저 할 것

어느 안을 고르든 `REVIEW_REQUIRED` 건수 지표와 알림이 먼저 필요하다. 얼마나 쌓이는지 보지 않으면 TTL 연장이 필요한지 판단할 근거가 없다. 지표를 붙이고 실제 발생량을 확인한 뒤에 정한다.

함께 확인할 것:

- 카드 거절이 항상 4xx로 오는지, 2xx에 `DONE`이 아닌 `status`로 오는 경로는 없는지. 후자라면 [결제 수단별 확정 시점](#결제-수단별-확정-시점--확인-필요) 문제와 같은 뿌리다
- 승인 거절 시 게이트웨이 결제 상태가 `ABORTED`로 확정되는지. 우리 코드는 조회에서 `ABORTED`를 보면 확정 실패로 다루지만, 4xx 거절이 `ABORTED`를 만든다는 근거는 저장소에 없다
- 결제창 연동 방식(리다이렉트, 팝업)에 따라 거절 후 사용자가 어느 화면으로 돌아오는지
- 재결제에 걸리는 실제 시간 분포. TTL 연장 폭을 정하려면 필요하다
- `paymentKey`가 결제창 세션당 하나인지. 우리 문서에는 그렇게 적혀 있으나 게이트웨이가 보장한다는 외부 근거는 없다

## 결제 수단별 확정 시점 — 확인 필요

`TossPaymentGateway.confirm()`이 승인 응답의 `status`를 읽지 않고, 2xx면 곧바로 승인 성공으로 다룬다. `query()`는 `status`로 분기하지만 `confirm()`에는 그 분기가 없다. `TossPaymentConfirmResponse`에는 `status` 필드가 이미 있으므로 읽기만 하면 된다.

카드와 간편결제는 승인 응답이 `DONE`이라 문제가 없다. 가상계좌는 승인 응답이 `WAITING_FOR_DEPOSIT`이고 **입금 전에도 2xx가 돌아온다.** 지금 코드는 이것을 `SUCCEEDED`로 확정하고 예매까지 발행하므로, 입금하지 않은 승차권이 나간다.

`PaymentMethod`에 `VIRTUAL_ACCOUNT`와 `TRANSFER`가 있고 `TossPaymentGateway.mapMethod`도 두 값을 매핑한다. 다만 결제 위젯에서 실제로 노출되는 수단은 Toss 대시보드 설정이라 저장소에서 확인할 수 없다.

### 먼저 확인할 것

- Toss 대시보드에서 가상계좌와 계좌이체가 켜져 있는지
- 꺼져 있다면 앞으로 켤 계획이 있는지

둘 다 아니라면 `PaymentMethod`에서 해당 값을 지우거나, 승인 응답이 `DONE`이 아닐 때 거절하는 방어만 두고 끝낸다.

### 켤 경우 결정할 것

| 항목 | 내용 |
|---|---|
| 확정 조건 | 승인 응답 `status == DONE`일 때만 `SUCCEEDED`로 확정한다. `WAITING_FOR_DEPOSIT`은 별도 처리 |
| 입금 대기 표현 | 승인은 성공했으나 입금 전인 상태를 어떻게 남길지. 기존 `IN_PROGRESS`를 재사용하면 Recovery Worker가 결과 불명으로 오인하므로 구분이 필요하다 |
| 좌석 확보 | 입금 기한이 보통 며칠이라 예약 TTL 10분과 맞지 않는다. 입금 전까지 좌석을 어떻게 잡아둘지 정해야 한다 |
| 입금 통지 | 입금 완료는 Webhook(`DEPOSIT_CALLBACK`)으로만 알 수 있다. 가상계좌를 켜면 Webhook(#291)이 선택이 아니라 필수가 된다 |
| 입금 오류 | Toss v1.4 이후 입금 오류 시 `DONE`에서 `WAITING_FOR_DEPOSIT`으로 되돌아간다. 확정 후 되돌아오는 유일한 경로이므로 발권 이후라면 `REVIEW_REQUIRED`로 분리한다 |
| 기한 만료 | 입금 기한이 지나면 좌석을 해제하고 주문을 정리하는 경로가 필요하다 |

### 범위 판단

가상계좌를 지원하려면 확정 시점과 좌석 확보 정책이 카드와 완전히 달라진다. 결제 수단 하나를 늘리는 작업이 아니라 **별도 흐름을 하나 더 만드는 작업**에 가깝다. 승인 응답 `status` 검사만 먼저 넣어 입금 전 발권을 막고, 가상계좌 지원 여부는 별도 이슈에서 판단한다.

## 취소 멱등키 — 파생 규칙을 쓰지 않는다

`TossPaymentClient.cancelPayment`는 `Idempotency-Key` 헤더를 보내지만 값이 `UUID.randomUUID()`다. 호출할 때마다 새 키라서 같은 취소를 재시도하면 게이트웨이가 별개 요청으로 받는다.

규칙이 없어서가 아니다. `PaymentAttemptIds.forCancellation(paymentKey, cancellationSequence)`가 `sha256Hex("cnl:" + paymentKey + ":" + sequence)`로 이미 구현돼 있고 단위 테스트도 있다. 승인의 `apv:` 규칙과 나란히 있으며 부분 취소를 sequence로 구분하는 의도까지 javadoc에 적혀 있다. `cancelPayment`가 그 규칙을 쓰지 않는 것이 실제 갭이다.

`forCancellation`은 프로덕션 호출자가 없고 `cancelPayment`도 호출자가 타이머 aspect뿐이라 취소 경로 전체가 아직 배선 전이다. 지금 바꿔도 회귀 위험이 없다.

열린 문제는 하나다. **취소 단위를 승차권으로 잡으면 `cancellationSequence`를 무엇으로 채울지 정해야 한다.** 결제당 취소 횟수를 세는 값이면 동시 취소에서 같은 sequence가 나올 수 있고, 취소 대상 승차권 집합에서 파생하면 같은 승차권을 두 번 취소하는 요청이 같은 키가 되어 의도한 멱등성이 된다. 취소 API 설계와 함께 정한다.

`confirmPayment`에는 `Idempotency-Key` 헤더가 없다. 같은 `paymentKey`로 두 번 승인되지 않는 것은 헤더가 아니라 `paymentKey`가 결제창 세션당 하나라는 성질과 우리 쪽 `attempt_id` unique 제약에서 온다. 앞의 성질은 우리 문서에만 있는 서술이라 [먼저 할 것](#먼저-할-것)의 확인 목록에 함께 올려 두었다.

## Metrics — 후속 이슈에서 도입

| Name | Type | Purpose |
|------|------|---------|
| `payment.attempt.in_progress` | Gauge | 진행 중 attempt 수 |
| `payment.attempt.recovered` | Counter | Recovery Worker가 복구한 건수 |
| `payment.outbox.pending` | Gauge | 미처리 outbox 행 수 |
| `payment.outbox.failed` | Counter | FAILED 전이 건수 (최대 재시도 초과 + 재시도 불가) |
| `payment.outbox.non_retryable` | Counter | 재시도 불가로 백오프 없이 FAILED 전이된 건수. `failed`의 부분집합 |
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
