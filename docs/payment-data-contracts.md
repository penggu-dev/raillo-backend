# 결제 데이터 계약

결제·예약 흐름을 이해하기 위한 실제 저장 형식 요약. 코드가 단일 원본이며 이 문서는 사람용 요약이다. 세부는 각 클래스/스크립트 참고.

관련 다이어그램: [`docs/diagrams/payment-flow/`](./diagrams/payment-flow/) 아래 `full-flow.html`, `confirm-detail.html`, `normal.html`, `card-rejected.html`, `unknown-result-inline-recovery.html`, `unknown-result-user-retry.html`.

## Redis — 예약과 좌석 점유

키·값 계약(예약 본문 JSON, 좌석 점유 Hash field, 회원 인덱스, 각 TTL)은 `raillo-domain/booking/cache/ReservationCacheKey`가 단일 원본이고, 문서 쪽 단일 원본은 **[reservation-cache-schema.md](./reservation-cache-schema.md) §1~§3·§5**다.

여기서 같은 내용을 다시 적지 않는다. 두 곳에 두는 동안 실제로 어긋났다 — 회원 인덱스 field TTL을 "본문보다 20초 짧게"로 적어 뒀지만 코드는 본문과 같은 `ttl`을 쓴다(`ReservationService.reserve` → `indexForMember`).

## MySQL — 주문·결제·시도

### Order

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `order_id` | PK | |
| `order_code` | String | 유저 노출 주문 번호. Payment.order_code와 동일 |
| `member_id` | FK (constraint 없음) | |
| `total_amount` | BigDecimal | |
| `order_status` | Enum | `PENDING`, `ORDERED`, `EXPIRED` |
| `created_at`, `updated_at` | Timestamp | BaseEntity |

- 결제 승인 성공 시 `Order.completePayment()` → `ORDERED` 전이

### OrderBooking

- `order`와 `reservation`을 잇는 조인 엔티티. 예약별로 하나

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `order_booking_id` | PK | |
| `pending_booking_id` | String | ⚠️ 물리 컬럼명. Java 필드는 `reservationId`. 별도 마이그레이션으로 `reservation_id`로 rename 예정 |
| `order_id` | FK | |
| `train_schedule_id` | FK | |
| `departure_stop_id`, `arrival_stop_id` | FK | |
| `total_fare` | BigDecimal | |
| `reservation_snapshot` | TEXT | Reservation JSON 원본 사본. Redis 본문이 만료돼도 recovery 가능 |

### OrderSeatBooking

- OrderBooking의 좌석별 상세. `seatId`, `passengerType`, `fare` 저장

### Payment

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `payment_id` | PK | |
| `order_id` | FK | Order와 1:1 |
| `member_id` | FK | |
| `order_code` | String | Order.order_code 사본 |
| `payment_key` | String, unique nullable | ⚠️ **재설계 후: 승인 확정 시에만 세팅**. 확정된 결제 키(Toss 취소/환불 호출 시 사용). PENDING 상태에서는 null |
| `amount` | BigDecimal | |
| `payment_method` | Enum | 성공 시 세팅 |
| `payment_status` | Enum | `PENDING`, `PAID`, `CANCELLED`, `REFUNDED`, `FAILED` |
| `paid_at`, `failed_at`, `cancelled_at`, `refunded_at` | Timestamp | 각 상태 전이 시각 |
| `failure_code`, `failure_message` | String | Toss PG 실패 원인 |

- **상태 전이**: `PENDING` → `PAID`(승인 성공) / `CANCELLED`(유저 취소) / `REFUNDED`(환불) / `FAILED`(취소 도메인, 유저 취소·예약 만료 배치에서만)
- ⚠️ **재설계 후**: Attempt 실패로는 `FAILED` 전이 안 함. Payment는 PENDING 유지

### PaymentAttempt

- Payment 하나에 여러 Attempt. 각 결제창 세션(각 paymentKey)마다 하나

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `payment_attempt_id` | PK | |
| `payment_id` | FK | 인덱스 있음 |
| `attempt_id` | String(64), unique | `SHA256(apv:paymentKey)` 파생. **paymentKey당 유일** |
| `attempt_type` | Enum | `APPROVAL`, `CANCELLATION` |
| `status` | String(20) | `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `REVIEW_REQUIRED`, `NOT_SENT`. 인덱스 있음. ENUM이 아니라 VARCHAR 매핑 — 아래 `status 컬럼` 참고 |
| `payment_key` | String | TX A에서 사전 저장 → recovery용 durable key |
| `error_code`, `error_message` | String | 실패 시(예: Toss 오류 코드, `GATEWAY_{상태}`), 수동 확인 시(`REVIEW_SEAT_LOST`, `REVIEW_DEPARTED`, `REVIEW_RESULT_MISMATCH`) |
| `processing_owner`, `processing_lease_until` | Recovery Worker 리스 관리용 |

#### `status` 컬럼

MySQL ENUM이 아니라 VARCHAR로 매핑한다(`@Enumerated(EnumType.STRING)`, `@JdbcTypeCode(SqlTypes.VARCHAR)`). 전환 SQL은 `docs/db-migrations/2026-09-26-payment-attempt-status-varchar.sql`.

**값 추가가 DB 작업 없이 끝나는 범위는 ALTER로 전환한 컬럼뿐이다.** 개발·운영 DB는 여기 해당한다. 엔티티 매핑으로 스키마를 새로 만드는 환경(테스트 컨테이너 등)은 Hibernate가 `status varchar(20) not null check (status in (...))` 형태의 CHECK 제약을 함께 만들고 `ddl-auto: update`는 기존 CHECK를 갱신하지 않는다. 그래서 새 상태 값은 그런 환경에서만 거부될 수 있다.

`NOT_SENT`는 요청이 게이트웨이에 도달하지 않은 것이 확정인 attempt다. 비종결 상태이며 같은 attemptId로 다시 승인하면 `IN_PROGRESS`로 되돌아간다. 같은 결제의 새 attempt 요청에는 `PAYMENT_GATEWAY_NOT_SENT`(`PAYMENT_116`, 503)를 응답한다. 값 추가에 DB 작업은 필요 없었다. 이미 VARCHAR로 전환한 컬럼이다.

`REVIEW_REQUIRED`는 Toss에서 승인됐지만 자동으로 확정하지 않는 attempt다. 같은 결제의 새 attempt 요청에는 `PAYMENT_ATTEMPT_REVIEW_REQUIRED`(`PAYMENT_117`)를 응답한다.


- **인덱스**: `idx_payment_attempt_payment_id`, `idx_payment_attempt_status_type`, `uk_payment_attempt_attempt_id`(unique)
- **dedup 계약**: `attemptId` unique 제약이 서버 측 idempotency 역할. 같은 attemptId 두 번째 insert는 DB에서 거부됨
- **조회 결과 불명**: 결제 조회가 timeout이나 응답 본문 끊김으로 끝나면 `TossPaymentClient.queryPayment`가 `QUERY_UNCERTAIN_{TIMEOUT|BODY|IO}` 코드(504 또는 502)로 알린다. 본문 읽기 실패는 최대 2회 다시 조회한다. 4xx가 아니므로 사용자 재시도는 attempt를 IN_PROGRESS로 남기고 재시도를 안내한다.

### PaymentOutbox

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `payment_outbox_id` | PK | |
| `aggregate_id` | Long | Payment ID |
| `deduplication_key` | String, unique | 예: `payment:{paymentId}:booking-confirmed` |
| `type` | Enum | `BOOKING_CONFIRMED` (⚠️ 재설계 후 `RESERVATION_RELEASE` 삭제) |
| `payload` | TEXT (JSON) | 아래 참고 |
| `status` | Enum | `PENDING`, `DONE`, `FAILED` |
| `retry_count` | int | |
| `next_retry_at` | Timestamp | 지수 backoff |

- **Worker**: `PaymentOutboxWorker.poll()`이 `FOR UPDATE SKIP LOCKED`로 배치 선점, dispatcher가 REQUIRES_NEW로 처리기 격리

## Outbox Payload — `BookingConfirmedPayload` v2

TX B에서 Payment 승인 확정 시 함께 커밋되는 이벤트. payload는 Redis 예약이 아니라 `OrderBooking.reservation_snapshot`으로 만든다. `BookingConfirmedProcessor`가 소비해 좌석을 `R:` → `B:`로 전환한다.

```json
{
  "schemaVersion": 2,
  "paymentId": 501,
  "attemptId": "3c6f...",
  "bookings": [
    {
      "reservationId": "RV20260918143000A1B2C3",
      "bookingId": 77,
      "memberNo": "202601010001",
      "trainScheduleId": 1001,
      "operationDate": "2026-10-20",
      "departureStopOrder": 0,
      "arrivalStopOrder": 3,
      "seats": [
        { "seatId": 46456, "trainCarId": 231 }
      ]
    }
  ]
}
```

- `bookings[].bookingId`는 TX B에서 새로 생성된 Booking의 DB PK
- `BookingConfirmedProcessor`가 이 payload의 스냅샷으로 `R:reservationId` → `B:bookingId` 좌석 field를 전환한다

## 흐름별 데이터 변화 요약

| 흐름 | Redis 예약 | Redis 좌석 field | Order | Payment | PaymentAttempt | Outbox |
|---|---|---|---|---|---|---|
| Prepare | 그대로 | 그대로 | INSERT (PENDING) | INSERT (PENDING, paymentKey=null) | — | — |
| Confirm 성공 (TX B) | 그대로 | 그대로 (R 유지, Outbox 처리 후 B) | UPDATE (ORDERED) | UPDATE (PAID, paymentKey=X) | UPDATE (SUCCEEDED) | INSERT (BOOKING_CONFIRMED PENDING) |
| BOOKING_CONFIRMED 처리 (R→B) | DEL (예약 본문 + 회원 인덱스) | R→B 전환 | 그대로 (이미 ORDERED) | 그대로 (이미 PAID) | 그대로 (이미 SUCCEEDED) | UPDATE (DONE) |
| Confirm 실패 (Toss 4xx) | 그대로 | 그대로 | 그대로 (PENDING) | 그대로 (PENDING) | UPDATE (FAILED, error_code) | — |
| 결과 불명 (인라인 재조회 통과) | 그대로 | 그대로 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공 시 INSERT |
| 결과 불명 (인라인 재조회 실패) | 그대로 | 그대로 | 그대로 (PENDING) | 그대로 (PENDING) | 그대로 (IN_PROGRESS) | — |
| 유저 재시도 + Toss GET | 그대로 | 그대로 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공 시 INSERT |
| 예약 TTL 만료 | DEL (자연) | 자연 만료 | 그대로 (PENDING 상태로 남음, 배치 정리 대상) | 그대로 (PENDING) | 그대로 | — |
| 유저 명시 취소 (#259) | DEL | HDEL | UPDATE (EXPIRED 등) | UPDATE (CANCELLED) | — | INSERT (BOOKING_CANCELLED) |

**핵심 원칙**: Redis 좌석 field와 예약 본문은 결제 시도 중에는 성공/실패에 영향받지 않는다. 승인 확정 뒤에는 `BookingConfirmedProcessor`가 좌석 field를 `R:`에서 `B:`로 바꾸고 예약 본문과 회원 인덱스를 지우며, 그 전까지는 TTL과 취소 도메인만 회수 근거다.
