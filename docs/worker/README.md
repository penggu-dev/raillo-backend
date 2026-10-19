# Worker

## 하는 일

`raillo-api` 안에서 요청과 별개로 주기적으로 도는 작업이다. `@Scheduled`로 실행한다. 정해진 시각에 한 번 끝나는 일은 [batch](../batch/README.md)가 맡는다.

| Worker | 주기 | 하는 일 |
|---|---|---|
| `PaymentOutboxWorker` | 5초 간격 (`raillo.payment.outbox.polling-interval`) | 결제 Outbox를 처리한다 |
| `PaymentRecoveryWorker` | `raillo.payment.recovery.polling-interval` | 오래 진행 중인 결제 승인 시도를 Toss와 대사한다 |
| `CalendarCacheEvictScheduler` | 매일 00:00 | 운행 캘린더 캐시(`train:calendar`)를 비운다 |

Outbox Worker와 복구 Worker는 둘 다 결제를 다루지만 역할이 다르다.

| | Outbox Worker | 복구 Worker |
|---|---|---|
| 다루는 사실 | 이미 정해진 사실(TX B가 커밋한 결과)을 전파한다 | 정해지지 않은 사실(Toss가 승인했는지 모름)을 Toss에 물어 알아내고 정한다 |
| 행이 생기는 시점 | TX B가 커밋될 때 생긴다 | TX B가 커밋되지 못한 시도를 대상으로 한다 |
| 못 보는 사고 | TX B 자체가 커밋되지 못한 사고는 Outbox 행이 없어 볼 수 없다 | - |
| 자세한 내용 | 아래 [결제 Outbox](#결제-outbox) | [결제](../payment/README.md)의 `PaymentApprovalReconciler`, `ReconcileDecision` |

결제 Outbox는 이렇게 동작한다.

- DB를 바꾸는 트랜잭션 안에서 **나중에 Redis에 해야 할 일**을 `payment_outbox`에 한 행으로 남긴다.
- Worker는 처리할 차례가 된 행을 가져와 처리하고, 실패하면 시간을 두고 다시 시도한다.
- 처리하는 일은 두 가지다.

| 종류(`PaymentOutboxType`) | 남기는 곳 | 하는 일 | `aggregateId` |
|---|---|---|---|
| `BOOKING_CONFIRMED` | 결제 승인 확정 | 좌석 점유를 예약(`R:`)에서 예매(`B:`)로 바꾼다 | 결제 ID |
| `BOOKING_SEAT_RELEASE_REQUIRED` | 예매 삭제 | 삭제된 예매의 `B:` 점유를 지운다 | 예매 ID |

## 용어

| 한국어 | 영어 | 설명 |
|---|---|---|
| Outbox 행 | PaymentOutbox | 나중에 처리할 일 하나. 종류, payload JSON, 상태, 재시도 횟수, 다음 시도 시각을 가진다 |
| 처리기 | OutboxEventProcessor | 행 종류 하나를 처리하는 구현. `BookingConfirmedProcessor`가 있다 |
| 중복 방지 키 | deduplicationKey | 같은 일을 두 번 넣지 않기 위한 유니크 키. `{집합체}:{id}:{이벤트}` 형식이고 `PaymentOutboxType`이 만든다. 예: `payment:{id}:booking-confirmed`, `booking:{id}:seat-release` |

## 결제 Outbox

### Outbox 행(PaymentOutbox)
Aggregate Root

- 속성: `aggregateId`(종류마다 뜻이 다르다), `type`, `payload`, `status`, `retryCount`, `nextRetryAt`, `deduplicationKey`
- 행위: `static forBookingConfirmed(...)`, `static forBookingSeatRelease(...)`, `markDone()`, `markRetry(nextRetryAt)`, `markFailed()`
- 규칙
  - 상태는 `PENDING` → `DONE` 또는 `FAILED`다. `FAILED`는 사람이 확인해야 한다
  - 중복 방지 키는 유니크다. 키 형식은 `PaymentOutboxType` 한 곳에서 만들고 호출하는 쪽에서 문자열을 잇지 않는다

### 처리 규칙(PaymentOutboxWorker)

- `PENDING`이고 다음 시도 시각이 지난 행을 50개씩 `SELECT ... FOR UPDATE SKIP LOCKED`로 가져온다
- 행마다 새 트랜잭션(`REQUIRES_NEW`)에서 처리기를 부른다
- 실패하면
  - 에러 코드가 `failedWorkRetryable() == false`면 바로 `FAILED`
  - 재시도 횟수가 5에 닿으면 `FAILED`
  - 그 외에는 30초부터 2배씩, 최대 10분 뒤에 다시 시도한다
  - 에러 코드가 없는 예외(연결 끊김, 타임아웃)는 재시도 대상이다

### 예매 점유 전환(BookingConfirmedProcessor)

- payload의 예약마다
  - 운행일이 지났으면 `reservation_delete.lua`로 좌석 점유와 예약 본문, 회원 인덱스를 되돌린다
  - 예매가 더는 유효하지 않으면(취소, 삭제) 같은 방식으로 되돌린다. 결제 중 보호로 만료가 사라진 field를 남기지 않기 위해서다
  - 그 외에는 `reservation_booking_confirm.lua`로 좌석 점유를 예매로 바꾼다
- 규칙
  - 다른 예약이나 예매와 충돌한 항목이 있으면, 나머지 항목을 모두 처리한 뒤 예외를 한 번 던져 재시도에 맡긴다
  - 데이터 오염(`SEAT_OCCUPANCY_CORRUPTED`)이나 스크립트 실패(`SEAT_OCCUPANCY_SCRIPT_ERROR`)는 그 항목에서 바로 멈춘다. 오염은 재시도해도 낫지 않으므로 바로 `FAILED`가 된다
  - 이미 전환한 항목을 다시 처리해도 성공한다
  - payload의 `schemaVersion`이 2가 아니거나 읽을 수 없으면 처리하지 않는다. 이 오류는 재시도 불가로 선언돼 있지 않아 최대 재시도까지 돈 뒤 `FAILED`가 된다

### 예매 좌석 해제(BookingSeatReleaseProcessor)

- `Booking` 행이 이미 없으므로 payload만으로 `booking_seat_release.lua`를 부른다
- 규칙
  - 값이 자기 `B:{bookingId}`일 때만 지우므로 다시 실행해도 안전하다
  - 해제 0건은 좌석이 풀렸다는 뜻이 아니다. 스크립트가 함께 돌려주는 foreign 수가 0보다 크면 다른 주인이 쥐고 있다
  - payload의 `schemaVersion`이 1이 아니거나 구간 순서가 잘못됐으면 처리하지 않는다

### payload 형식을 바꿀 때

Outbox 행은 쓴 코드와 읽는 코드가 다른 배포본일 수 있다. 그래서 payload는 `schemaVersion`을 갖고, 소비자는 지원 버전과 다르면 거절한다. 검사가 없으면 Jackson이 없는 필드를 기본값으로 채워 엉뚱한 좌석을 건드린다.

1. 선택 필드 추가처럼 구 JSON으로도 동작하면 버전을 올리지 않는다. 필드가 사라지거나 의미, 타입, 필수 여부가 바뀌면 올린다.
2. 구 행을 payload만으로 새 형식으로 바꿀 수 있으면, 한 릴리스에서 소비자가 두 버전을 모두 읽게 하고 다음 릴리스에서 생산자를 바꾼다. 생산자와 소비자가 같은 `raillo-api`라 배포 순서로는 나눌 수 없다.
3. 바꿀 수 없으면 배포 전에 남은 행을 비운다. 그대로 두면 구 행은 재시도를 다 쓰고 `FAILED`가 된다.

- 규칙
  - **`payment_outbox`에는 실패 원인 컬럼이 없다.** `FAILED` 행으로는 재시도를 다 썼다는 것만 알 수 있고, 원인은 로그와 지표에만 남는다
  - 해제가 0건이어도 좌석이 풀렸다는 뜻은 아니다. 전환 전에 지운 예매는 `R:` field를 남긴다

## 설계 결정

### 좌석 점유 전환은 결제 트랜잭션 밖에서 Outbox로 한다
- 맥락: 예매는 DB에, 좌석 점유는 Redis에 있다. 두 저장소를 한 트랜잭션으로 묶을 수 없다. 결제 트랜잭션 안에서 Redis를 바꾸면, Redis는 바뀌었는데 DB가 롤백되거나 그 반대가 생긴다.
- 결정: 결제 트랜잭션은 DB(예매, Outbox 행)만 커밋한다. Redis 전환은 Worker가 Outbox 행을 읽어 따로 한다.
- 결과: DB가 커밋되면 Redis 전환은 결국 일어난다. 그 사이 예약 TTL이 먼저 끝나면 결제한 좌석이 Redis에서 잠시 빈 좌석이 된다. 다른 승객이 그 좌석을 예약할 수는 있지만, 결제 준비의 DB 재검증에서 막힌다.
- 예매 삭제의 좌석 해제도 같은 이유로 Outbox로 한다. 해제는 삭제가 커밋된 뒤여야 R→B 전환 재처리와 겹치지 않는데, 커밋 뒤에 Redis를 직접 부르면 실패했을 때 되돌릴 곳이 없다.

### 행마다 새 트랜잭션에서 처리한다
- 맥락: 처리기 안의 `@Transactional` 서비스가 예외를 던지면 바깥 트랜잭션이 rollback-only가 된다. 그러면 같은 배치의 성공한 행과 실패 행의 재시도 상태까지 함께 롤백된다.
- 결정: 행을 가져오는 트랜잭션과 처리하는 트랜잭션을 나눈다. 처리는 `REQUIRES_NEW`로 한다.
- 결과: 한 행의 실패가 다른 행에 번지지 않는다.

### 재시도 여부는 에러 코드가 정한다
- 맥락: 데이터 오염처럼 몇 번을 다시 해도 같은 결과가 나오는 실패에 백오프를 태우면, 사람이 알아차리는 시점만 늦어진다.
- 결정: `ErrorCode.failedWorkRetryable()`이 `false`인 실패는 바로 `FAILED`로 보낸다. 예외 클래스가 아니라 에러 코드로 판단한다. 에러 코드를 싣는 예외가 셋이라 클래스를 나열하면 새 예외가 빠지기 때문이다.
- 결과: 새 에러 코드를 만들 때 재시도해도 소용없는 오류면 `failedWorkRetryable()`을 `false`로 오버라이드해야 한다.

### 여러 인스턴스가 같은 행을 처리하지 않게 `SKIP LOCKED`로 가져온다
- 맥락: API 서버가 여러 대면 모든 인스턴스의 Worker가 같은 테이블을 폴링한다.
- 결정: 행을 비관적 잠금 + `SKIP LOCKED`로 가져온다. 다른 인스턴스가 잡은 행은 건너뛴다.
- 결과: 별도 분산 락 없이 인스턴스를 늘릴 수 있다. 지금 운영 API 서버는 1대다.
