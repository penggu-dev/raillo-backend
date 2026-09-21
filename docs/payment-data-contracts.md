# 결제 데이터 계약

결제·예약 흐름을 이해하기 위한 실제 저장 형식 요약. 코드가 단일 원본이며 이 문서는 사람용 요약이다. 세부는 각 클래스/스크립트 참고.

관련 다이어그램: [`docs/diagrams/payment-flow/`](./diagrams/payment-flow/) 아래 `full-flow.html`, `confirm-detail.html`, `normal.html`, `card-rejected.html`, `unknown-result-inline-recovery.html`, `unknown-result-user-retry.html`.

## Redis — 예약과 좌석 점유

키 계약은 `raillo-domain/booking/cache/ReservationCacheKey`가 단일 원본. 자세한 흐름은 [`docs/reservation-cache-schema.md`](./reservation-cache-schema.md).

### 예약 본문

- **키**: `{schedule:{trainScheduleId}}:reservation:{reservationId}` — String
- **값**: `Reservation` record JSON

```json
{
  "reservationId": "RV20260918143000A1B2C3",
  "memberNo": "202601010001",
  "trainScheduleId": 1001,
  "trainNumber": 101,
  "trainName": "KTX 산천",
  "operationDate": "2026-10-20",
  "departure": { "stopId": 42, "stopOrder": 0, "stationId": 5, "stationName": "서울" },
  "arrival":   { "stopId": 45, "stopOrder": 3, "stationId": 12, "stationName": "부산" },
  "departureAt": "2026-10-20T08:00:00",
  "carType": "STANDARD",
  "seats": [
    { "seatId": 46456, "trainCarId": 231, "passengerType": "ADULT", "fare": 43210 }
  ],
  "totalFare": 43210,
  "createdAt": "2026-10-19T09:00:00",
  "expiresAt": "2026-10-19T09:10:00"
}
```

- **TTL**: 생성 시점 `expiresAt`까지 (기본 10분)
- **소유권**: `memberNo` 필드. 조회 시 요청 회원과 일치 검증
- **잠금 기한**: 출발 시각 5분 전(`Reservation.BOOKING_CLOSE_BEFORE_DEPARTURE`) 이후에는 새 예약 생성 불가, TTL도 여기서 끊김

### 좌석·구간 점유 Hash

- **키**: `{schedule:{trainScheduleId}}:car:{trainCarId}:seats` — Hash
- **field**: `{seatId}:{sectionIndex}` — 좌석 하나의 구간 하나
- **값**: `R:{reservationId}` 또는 `B:{bookingId}`
  - `R:` — 예약(임시). field에 예약 TTL 걸림 (`HEXPIRE`)
  - `B:` — 예매(확정). field TTL 없음, 운행 Hash 키 TTL로만 만료
- **Hash 키 TTL**: 운행일 + 2일의 한국 시간 자정(`TrainCacheKey.expireAtEpochSecond`)에 절대 만료(`EXPIREAT`). 상대 TTL 금지.
- **구간 index 규약**: 출발 stopOrder `d`, 도착 stopOrder `a`인 요청은 `[d, a-1]` 범위의 구간을 점유

### 회원 예약 인덱스

- **키**: `member:{memberNo}:reservations` — Hash
- **field**: `{reservationId}`, **값**: `{trainScheduleId}` (숫자 문자열)
- 회원별 예약 조회에 사용. field TTL은 예약 본문보다 20초 짧게 설정한다. 인덱스는 살아 있는데 본문이 이미 만료된 상태를 피하기 위해서다.

### 재검토로 이번 브랜치에서 제거된 항목

- `{schedule:X}:reservation-payment:{reservationId}` marker와 관련 Lua 스크립트 두 개(`reservation_payment_claim.lua`, `reservation_payment_release.lua`)를 함께 제거했다. 동시 결제 방지는 `PaymentAttempt.attempt_id`의 DB unique 제약과 `PaymentValidator.validateApprovable(payment)`로 커버된다.

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
| `status` | Enum | `IN_PROGRESS`, `SUCCEEDED`, `FAILED`. 인덱스 있음 |
| `payment_key` | String | TX A에서 사전 저장 → recovery용 durable key |
| `error_code`, `error_message` | String | 실패 시 |
| `processing_owner`, `processing_lease_until` | Recovery Worker 리스 관리용 |

- **인덱스**: `idx_payment_attempt_payment_id`, `idx_payment_attempt_status_type`, `uk_payment_attempt_attempt_id`(unique)
- **dedup 계약**: `attemptId` unique 제약이 서버 측 idempotency 역할. 같은 attemptId 두 번째 insert는 DB에서 거부됨

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

TX B에서 Payment 승인 확정 시 함께 커밋되는 이벤트. 후속 R→B 처리기가 소비(현재 미등록 상태로 PENDING 보존).

```json
{
  "schemaVersion": 2,
  "paymentId": 501,
  "attemptId": "3c6f...",
  "guardToken": "501:1",
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

- ⚠️ **재설계 후**: `guardToken` 필드는 삭제 예정 (guard 스택 삭제와 함께)
- `bookings[].bookingId`는 TX B에서 새로 생성된 Booking의 DB PK
- 후속 처리기는 이 payload의 스냅샷으로 `R:reservationId` → `B:bookingId` 좌석 field 전환

## 흐름별 데이터 변화 요약

| 흐름 | Redis 예약 | Redis 좌석 field | Order | Payment | PaymentAttempt | Outbox |
|---|---|---|---|---|---|---|
| Prepare | 그대로 | 그대로 | INSERT (PENDING) | INSERT (PENDING, paymentKey=null) | — | — |
| Confirm 성공 (TX B) | 그대로 | 그대로 (R로 유지, 후속 PR에서 B) | UPDATE (ORDERED) | UPDATE (PAID, paymentKey=X) | UPDATE (SUCCEEDED) | INSERT (BOOKING_CONFIRMED PENDING) |
| Confirm 실패 (Toss 4xx) | 그대로 | 그대로 | 그대로 (PENDING) | 그대로 (PENDING) | UPDATE (FAILED, error_code) | — |
| 결과 불명 (인라인 재조회 통과) | 그대로 | 그대로 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공 시 INSERT |
| 결과 불명 (인라인 재조회 실패) | 그대로 | 그대로 | 그대로 (PENDING) | 그대로 (PENDING) | 그대로 (IN_PROGRESS) | — |
| 유저 재시도 + Toss GET | 그대로 | 그대로 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공/실패 케이스로 귀결 | 성공 시 INSERT |
| 예약 TTL 만료 | DEL (자연) | 자연 만료 | 그대로 (PENDING 상태로 남음, 배치 정리 대상) | 그대로 (PENDING) | 그대로 | — |
| 유저 명시 취소 (#259) | DEL | HDEL | UPDATE (EXPIRED 등) | UPDATE (CANCELLED) | — | INSERT (BOOKING_CANCELLED) |

**핵심 원칙**: Redis 좌석 field와 예약 본문은 결제 시도의 성공/실패에 영향받지 않는다. TTL과 취소 도메인만 회수 근거.
