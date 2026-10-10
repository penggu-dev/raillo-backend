# Worker

`raillo-api` 안에서 요청과 별개로 주기적으로 도는 작업이다. `@Scheduled`로 실행한다. 정해진 시각에 한 번 끝나는 일은 [batch](../batch/README.md)가 맡는다.

| Worker | 주기 | 하는 일 |
|---|---|---|
| `PaymentOutboxWorker` | 5초 간격 (`raillo.payment.outbox.polling-interval`) | 결제 Outbox를 처리한다 |
| `CalendarCacheEvictScheduler` | 매일 00:00 | 운행 캘린더 캐시(`train:calendar`)를 비운다 |

## 결제 Outbox

### 하는 일

- 결제가 승인되면 같은 트랜잭션에서 예매를 만들고, **나중에 해야 할 일**을 `payment_outbox`에 한 행으로 남긴다.
- Worker는 처리할 차례가 된 행을 가져와 처리하고, 실패하면 시간을 두고 다시 시도한다.
- 지금 처리하는 일은 하나다. 결제가 확정된 예약의 Redis 좌석 점유를 예약(`R:`)에서 예매(`B:`)로 바꾸는 것이다(`BOOKING_CONFIRMED`).

### 용어

| 한국어 | 영어 | 설명 |
|---|---|---|
| Outbox 행 | PaymentOutbox | 나중에 처리할 일 하나. 종류, payload JSON, 상태, 재시도 횟수, 다음 시도 시각을 가진다 |
| 처리기 | OutboxEventProcessor | 행 종류 하나를 처리하는 구현. `BookingConfirmedProcessor`가 있다 |
| 중복 방지 키 | deduplicationKey | 같은 일을 두 번 넣지 않기 위한 유니크 키. 예: `payment:{id}:booking-confirmed` |

### 모델

#### Outbox 행(PaymentOutbox)
Aggregate Root

- 속성: `aggregateId` 결제 ID, `type`, `payload`, `status`, `retryCount`, `nextRetryAt`, `deduplicationKey`
- 행위: `static forBookingConfirmed(...)`, `markDone()`, `markRetry(nextRetryAt)`, `markFailed()`
- 규칙
  - 상태는 `PENDING` → `DONE` 또는 `FAILED`다. `FAILED`는 사람이 확인해야 한다
  - 중복 방지 키는 유니크다

#### 처리 규칙(PaymentOutboxWorker)

- `PENDING`이고 다음 시도 시각이 지난 행을 50개씩 `SELECT ... FOR UPDATE SKIP LOCKED`로 가져온다
- 행마다 새 트랜잭션(`REQUIRES_NEW`)에서 처리기를 부른다
- 실패하면
  - 에러 코드가 `failedWorkRetryable() == false`면 바로 `FAILED`
  - 재시도 횟수가 5에 닿으면 `FAILED`
  - 그 외에는 30초부터 2배씩, 최대 10분 뒤에 다시 시도한다
  - 에러 코드가 없는 예외(연결 끊김, 타임아웃)는 재시도 대상이다

#### 예매 점유 전환(BookingConfirmedProcessor)

- payload의 예약마다
  - 운행일이 지났으면 좌석은 두고 예약 본문과 회원 인덱스만 지운다
  - 예매가 더는 유효하지 않으면(취소, 삭제) 같은 방식으로 지운다
  - 그 외에는 `reservation_booking_confirm.lua`로 좌석 점유를 예매로 바꾼다
- 규칙
  - 다른 예약이나 예매와 충돌한 항목이 있으면, 나머지 항목을 모두 처리한 뒤 예외를 한 번 던져 재시도에 맡긴다
  - 데이터 오염(`SEAT_OCCUPANCY_CORRUPTED`)이나 스크립트 실패(`SEAT_OCCUPANCY_SCRIPT_ERROR`)는 그 항목에서 바로 멈춘다. 오염은 재시도해도 낫지 않으므로 바로 `FAILED`가 된다
  - 이미 전환한 항목을 다시 처리해도 성공한다
  - payload의 `schemaVersion`이 2가 아니거나 읽을 수 없으면 처리하지 않고 실패로 둔다

### 설계 결정

#### 좌석 점유 전환은 결제 트랜잭션 밖에서 Outbox로 한다
- 맥락: 예매는 DB에, 좌석 점유는 Redis에 있다. 두 저장소를 한 트랜잭션으로 묶을 수 없다. 결제 트랜잭션 안에서 Redis를 바꾸면, Redis는 바뀌었는데 DB가 롤백되거나 그 반대가 생긴다.
- 결정: 결제 트랜잭션은 DB(예매, Outbox 행)만 커밋한다. Redis 전환은 Worker가 Outbox 행을 읽어 따로 한다.
- 결과: DB가 커밋되면 Redis 전환은 결국 일어난다. 그 사이 예약 TTL이 먼저 끝나면 결제한 좌석이 Redis에서 잠시 빈 좌석이 된다. 다른 승객이 그 좌석을 예약할 수는 있지만, 결제 준비의 DB 재검증에서 막힌다.

#### 행마다 새 트랜잭션에서 처리한다
- 맥락: 처리기 안의 `@Transactional` 서비스가 예외를 던지면 바깥 트랜잭션이 rollback-only가 된다. 그러면 같은 배치의 성공한 행과 실패 행의 재시도 상태까지 함께 롤백된다.
- 결정: 행을 가져오는 트랜잭션과 처리하는 트랜잭션을 나눈다. 처리는 `REQUIRES_NEW`로 한다.
- 결과: 한 행의 실패가 다른 행에 번지지 않는다.

#### 재시도 여부는 에러 코드가 정한다
- 맥락: 데이터 오염처럼 몇 번을 다시 해도 같은 결과가 나오는 실패에 백오프를 태우면, 사람이 알아차리는 시점만 늦어진다.
- 결정: `ErrorCode.failedWorkRetryable()`이 `false`인 실패는 바로 `FAILED`로 보낸다. 예외 클래스가 아니라 에러 코드로 판단한다. 에러 코드를 싣는 예외가 셋이라 클래스를 나열하면 새 예외가 빠지기 때문이다.
- 결과: 새 에러 코드를 만들 때 재시도해도 소용없는 오류면 `failedWorkRetryable()`을 `false`로 오버라이드해야 한다.

#### 여러 인스턴스가 같은 행을 처리하지 않게 `SKIP LOCKED`로 가져온다
- 맥락: API 서버가 여러 대면 모든 인스턴스의 Worker가 같은 테이블을 폴링한다.
- 결정: 행을 비관적 잠금 + `SKIP LOCKED`로 가져온다. 다른 인스턴스가 잡은 행은 건너뛴다.
- 결과: 별도 분산 락 없이 인스턴스를 늘릴 수 있다. 지금 운영 API 서버는 1대다.
