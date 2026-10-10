# 예약(Reservation) Redis 스키마

예약 생성이 DB 없이 Redis(Valkey 9)만으로 조회·검증·원자적 점유를 끝내기 위한 키 계약이다. 기준정보 읽기 쪽 계약은 [train-cache-schema.md](./train-cache-schema.md)를 본다.

키 포맷은 `raillo-domain`의 `booking/cache` 패키지가 단일 원본이다. Batch가 나중에 예매 좌석을 다시 채울 때도 같은 클래스를 쓴다.

- 키: `ReservationCacheKey`
- 점유 값: `SeatOccupancyValue`
- 예약 본문: `booking/domain/Reservation` (JSON)

## 1. 키 목록

| 키 | 타입 | 만료 | 내용 |
|---|---|---|---|
| `{schedule:{trainScheduleId}}:car:{carId}:seats` | Hash | 키: 운행일 기준 EXPIREAT, 예약 field: HEXPIRE (결제 중 보호는 5장 예외) | 객차 하나의 좌석 점유 상태 |
| `{schedule:{trainScheduleId}}:reservation:{reservationId}` | String | EX = 예약 TTL | 예약 본문 JSON |
| `member:{memberNo}:reservations` | Hash | field별 HEXPIRE = 예약 TTL | 회원별 예약 인덱스. field 예약 ID → value 운행 ID |
| `{schedule:{trainScheduleId}}:reservation:{reservationId}:order` | String | EXPIREAT = 결제 마감 시각 | 예약을 가져간 주문의 orderCode. 계획 B가 쓰기 시작한다 |

운행 단위 키는 기준정보 캐시와 같은 `{schedule:id}` hash tag를 써서 Redis Cluster에서 한 운행의 점유·예약·기준정보가 같은 slot에 놓인다. 회원 인덱스는 운행과 무관하므로 별도 slot이며 Lua 밖에서 다룬다.

## 2. 좌석 점유 Hash

```text
{schedule:1001}:car:231:seats
  field  46456:0   value  R:RV20260917120000A1B2C3    예약 점유 (예약 TTL만큼 HEXPIRE, 결제 중 보호는 5장 예외)
  field  46456:1   value  R:RV20260917120000A1B2C3
  field  46457:2   value  B:77                        예매 점유 (만료 없음)
```

- field는 `{seatId}:{sectionIndex}`. 구간 index는 정차 순서 i에서 i+1로 가는 한 칸이며 값은 i다. 출발 stopOrder d, 도착 stopOrder a인 요청은 d..a-1 구간 field를 점유한다.
- 값은 `R:{reservationId}`(예약) 또는 `B:{bookingId}`(예매)다. 그 외 형식은 데이터 오염이다. 오염은 재시도로 낫지 않으므로 스크립트 실행 자체가 실패한 경우(`SEAT_OCCUPANCY_SCRIPT_ERROR`)와 다른 코드로 구분한다.
  - 점유를 **쓰거나 전환하는** 스크립트(`reservation_create`, `reservation_booking_confirm`, `reservation_payment_hold`)는 검사 단계에서 오염을 만나면 `"X"`를 돌려주고 호출자가 `SEAT_OCCUPANCY_CORRUPTED`(500)를 낸다. 아무것도 쓰지 않는다.
  - **해제** 스크립트(`reservation_delete`, `reservation_payment_release`, `booking_seat_release`)는 자기 값이 아닌 field를 건드리지 않으므로 오염을 보고하지 않고 건너뛴다. 결과 수(`released`, `restored`/`deleted`)만 줄어든다. 보호 해제의 경우 건너뛴 field가 좌석을 묶을 수 있다(§5).
  - **읽기** 경로(`SeatOccupancyQueryRepository`)는 field 이름이나 값이 계약과 다르면 `SEAT_OCCUPANCY_CORRUPTED`를 낸다. 좌석 하나가 아니라 그 호출 전체가 실패한다.
  - field **이름**(`{seatId}:{sectionIndex}`)의 오염은 쓰기·전환 스크립트가 구분하지 못한다. Lua가 숫자 변환에 실패한 자리는 응답에서 빠지고 Java는 형식 불일치로 읽어 `SEAT_OCCUPANCY_SCRIPT_ERROR`가 된다. 현재 의도된 한계다.
- 키는 점유가 처음 생길 때 만들어지고, TTL이 없을 때만 `TrainCacheKey.expireAtEpochSecond(운행일)`로 EXPIREAT을 건다. 빈 열차는 키가 없다.
- 예약 field는 HEXPIRE로 예약과 함께 사라진다. 별도 정리 작업이나 인덱스가 필요 없다. 필드 단위 만료는 Redis 7.4, Valkey 9.0부터 지원한다. 결제가 진행 중인 예약 field는 예외로 만료가 없을 수 있다(5장).
- 예매 점유(`B:`) 기록은 `PaymentOutboxWorker`가 승인 확정 후 `BOOKING_CONFIRMED` outbox를 `BookingConfirmedProcessor`에 넘겨 `R:` → `B:`로 전환한다(`reservation_booking_confirm.lua`).
  - 자기 예약 `R:`은 `B:{bookingId}`로 바꾸고 field 만료를 없앤다. 같은 `B:`는 그대로 성공한다. 빈 field는 DB 예매가 유효할 때만 `B:`를 쓴다.
  - 다른 R이나 B가 하나라도 있으면 아무것도 바꾸지 않고, 처리기가 예외를 던져 Outbox 재시도에 맡긴다.
  - 전환하면 예약 본문을 지우고, 별도 slot인 회원 인덱스 field는 Lua 밖에서 HDEL한다. 객차 키에 만료가 없으면 운행일 기준 EXPIREAT을 건다.
  - 운행일이 지났거나 예매가 없거나 취소된 항목은 좌석을 건드리지 않고 예약 본문과 회원 인덱스만 지운다.
  - `B:`는 만료가 없으므로 예매가 사라지면 반드시 지워 줘야 한다. 예매 삭제 경로가 §6-2다.

### R→B 충돌이 생기는 경우

처리기는 자기 `R:` field가 아직 좌석을 잡고 있다고 보고 전환한다. 자기 field는 예약 TTL(10분)을 따르므로 전환 전에 사라질 수 있고, 그 사이 다른 예약이 같은 좌석을 잡으면 충돌한다. 다른 예약은 결제 준비의 DB 재검증(`SeatConflictValidator`)에 막혀 결제할 수 없으므로 이중 판매로 이어지지 않는다. 그 예약은 최대 10분 뒤 사라지고, 그 뒤 재시도하면 전환된다. 같은 예약으로 만든 두 주문(#280)은 예외다. 두 주문 모두 결제 준비를 통과한 뒤 결제되면 DB에 이미 이중 판매가 생긴 것이고, R→B 충돌은 그것을 드러낼 뿐이다(표의 마지막 행).

| 경우 | 지금의 처리 | 후속 처리 |
|---|---|---|
| 예약 만료 직전에 결제해 Toss 응답을 기다리는 사이 자기 `R:`이 만료된다 | Outbox 재시도. 재시도 창(초기 30초, 최대 5회, 약 7.5분)이 예약 TTL보다 짧아 FAILED로 끝날 수 있다 | #270 후속 작업의 결제 중 좌석 보호(prepare에서 confirm 마감까지 연장, 결제 시작 시 연장, 결과 불명 시 HPERSIST)로 자기 field가 먼저 사라지지 않게 한다 |
| Worker 지연이나 장애가 남은 field TTL보다 길다 | 위와 같다 | #270 후속 작업에서 재시도 창을 예약 TTL보다 길게 조정해 다른 예약이 사라진 뒤 자동으로 전환되게 한다 |
| 처리기 배포 직후 쌓여 있던 행을 처리한다 | 쌓인 행의 자기 `R:`은 이미 만료됐다. 배포 순간 다른 예약이 잡고 있으면 위와 같이 재시도한다 | 일회성이다. FAILED가 되면 아래 재투입 SQL로 다시 처리한다 |
| 같은 예약으로 만든 두 주문이 전환 전에 모두 결제된다(#280) | 두 번째 전환이 `B:{다른 bookingId}`를 만나 재시도로 풀리지 않고 FAILED가 된다. DB에 같은 좌석 예매가 둘 생긴 것이다 | #280 주문 표시로 한 예약이 한 주문에만 쓰이게 해 막는다. 이미 생긴 건은 수동 확인과 환불(#259)로 정리한다 |

FAILED로 끝난 행은 처리기가 멱등하고 다른 점유를 바꾸지 않으므로 다시 넣어도 안전하다.

```sql
UPDATE payment_outbox SET status = 'PENDING', retry_count = 0, next_retry_at = NOW()
 WHERE type = 'BOOKING_CONFIRMED' AND status = 'FAILED'
   AND CASE WHEN NOT JSON_VALID(payload) THEN FALSE
            ELSE payload ->> '$.schemaVersion' = '2'
             AND JSON_EXTRACT(payload, '$.bookings') IS NOT NULL
       END;
```

`payload LIKE '%"schemaVersion":2%'`를 쓰면 안 된다. `":21"`이 `":2"`에 부분 매칭되어 **지원하지 않는 버전의 행까지 재투입**하고, 깨진 JSON도 앞부분에 `":2"`가 남아 있으면 통과하며, 공백이 섞인 v2(`"schemaVersion" : 2`)는 조용히 빠진다. 쓰기 문장이라 오분류가 그대로 상태 변경으로 이어진다. `JSON_VALID` 가드 없이 `->>`를 쓰면 깨진 행에서 `ERROR 3141`로 문장 전체가 죽으므로 `CASE`로 감싼다. 실행으로 확인한 오분류 사례와 검증된 쿼리는 [payment-outbox-payload-runbook.md](./payment-outbox-payload-runbook.md)에 있다.

### 예매 점유 백필 (필요할 때)

Redis에 `B:`가 없는 예매 좌석은 예약 생성 Lua가 빈 좌석으로 보고 예약을 만들어 준다. 결제 준비의 DB 재검증에서 막히므로 이중 판매는 없지만, 사용자는 예약한 뒤에야 거절된다. 검색과 좌석맵은 DB 예매와 Redis 점유를 합쳐 계산하므로 화면은 정상이다. 지금은 운영 데이터가 없어 백필하지 않으며, 아래 경우가 생기면 백필을 검토한다.

- R→B 처리기 배포 전에 만들어진 예매 중 운행일이 남은 것
- `BOOKING_CONFIRMED`가 FAILED로 끝났고 재투입하지 않은 예매
- 장애, flush, 새 클러스터 이전 등으로 운행 키가 사라진 경우. 기준정보 캐시 적재 Job(`trainStaticCacheLoad`, `trainScheduleCacheLoad`)은 좌석 점유를 채우지 않는다
- DB 복원이나 직접 적재로 예매가 Redis를 거치지 않고 들어온 경우

방법은 다음과 같다. raillo-batch Job으로 만들고 키와 값은 `ReservationCacheKey`, `SeatOccupancyValue`로 만든다.

1. 대상 예매 좌석을 조회한다. 운행일 비교는 `TrainCacheKey.ZONE`(Asia/Seoul) 기준 오늘이다.

   ```sql
   SELECT b.booking_id, ts.train_schedule_id, ts.operation_date, s.train_car_id, sb.seat_id,
          sb.departure_stop_order, sb.arrival_stop_order
   FROM seat_booking sb
   JOIN booking b ON b.booking_id = sb.booking_id
   JOIN train_schedule ts ON ts.train_schedule_id = sb.train_schedule_id
   JOIN seat s ON s.seat_id = sb.seat_id
   WHERE b.booking_status = 'BOOKED'
     AND ts.operation_date >= :today;
   ```

2. 행마다 `{schedule:{train_schedule_id}}:car:{train_car_id}:seats`의 `{seat_id}:{i}` field(i = 출발 stopOrder부터 도착 stopOrder - 1까지)에 `B:{booking_id}`를 쓴다. 객차 키 하나를 Lua 한 번으로 처리한다.
   - 빈 field에만 쓴다(HSETNX). 이미 값이 있으면 덮어쓰지 않는다. 같은 `B:{booking_id}`는 이미 채워진 것이고, 다른 `R:`은 결제 준비에서 막혀 만료될 예약이다. 다른 `B:`는 DB에서 좌석이 이중으로 팔렸다는 신호이므로 건너뛰고 로그로 남겨 확인한다.
   - field 만료는 걸지 않는다. 객차 키에 만료가 없을 때만 `TrainCacheKey.expireAtEpochSecond(operation_date)`로 EXPIREAT을 건다.
3. 멱등하므로 서비스 중에 실행해도 되고, 다시 실행해도 된다. 실행 중에 새로 생긴 예매는 R→B 처리기가 채운다.

## 3. 예약 본문

`Reservation` record를 평문 JSON으로 저장한다. Java 타입 메타데이터(`@class`)를 넣지 않고, 날짜·시각은 문자열이다. 조회 시 DB를 보지 않도록 표시용 값을 함께 담는다.

```json
{"reservationId":"RV20260917120000A1B2C3","memberNo":"202601010001","trainScheduleId":1001,
 "trainNumber":101,"trainName":"KTX","operationDate":"2026-10-20",
 "departure":{"stopId":9001,"stopOrder":0,"stationId":1,"stationName":"서울","time":"06:00:00"},
 "arrival":{"stopId":9004,"stopOrder":3,"stationId":5,"stationName":"부산","time":"08:52:00"},
 "departureAt":"2026-10-20T06:00:00","carType":"STANDARD",
 "seats":[{"seatId":46456,"trainCarId":231,"carNumber":3,"seatLabel":"12A","passengerType":"ADULT","fare":59800}],
 "totalFare":59800,"createdAt":"2026-09-17T12:00:00","expiresAt":"2026-09-17T12:10:00"}
```

`createdAt`은 초 단위로 잘라 저장한다. JSON 형식이 초까지라 왕복 후 값이 같아야 하기 때문이다.

## 4. 생성 흐름

1. 요청 검증: 승객 수 = 좌석 수, 좌석 중복 없음.
2. 기준정보 조회 (`TrainCacheQueryService`): 파이프라인 1회로 `{schedule}:info`, `{schedule}:stops`의 `st:{출발역}`·`st:{도착역}`, `train:seat:{id}` MGET, `train:fare` HGET을 읽는다. 운행·정차역·좌석이 없으면 404. 이어서 `train:traincar:{id}` MGET으로 좌석이 이 운행의 열차에 속하는지 확인한다. 객차가 없으면 `TRAIN_CAR_NOT_FOUND`, 다른 열차의 좌석이면 `SEAT_NOT_FOUND`다. 운임은 여기서 검사하지 않는다.
3. 운행 검증 (`ReservationValidator`): 운행 취소 → 정차 순서 → 운임 존재 → 출발 5분 전 마감 → 한 객차. 좌석이 두 객차 이상에 걸치면 `MULTIPLE_TRAIN_CARS`(400)다. 출발역과 도착역이 같거나 순서가 거꾸로면 정차 순서에서 `INVALID_ROUTE`(400)로 막힌다. 운임을 정차 순서 뒤에 보는 이유는 이런 요청이 운임 없음(404)으로 응답되지 않게 하기 위해서다.
4. 운임(`FareCalculator`, 캐시 운임)과 TTL(`ReservationService.calculateTtl`): min(10분, 마감까지 남은 시간), 정수 초 올림, 최소 1초.
5. 회원 인덱스 등록: HSETEX(`putAndExpire`)로 값과 field TTL을 한 번에.
6. `reservation_create.lua`: 요청 field를 HMGET해 자기 예약이 아닌 값이 하나라도 있으면 아무것도 쓰지 않고 충돌을 돌려준다. 없으면 HSET → HEXPIRE → (TTL 없을 때) EXPIREAT → SET 예약 EX.
7. 충돌·오류 시 회원 인덱스를 HDEL로 되돌린다.

인덱스를 Lua보다 먼저 쓰는 이유는 보상이 HDEL 하나로 끝나기 때문이다. 반대 순서면 점유를 푸는 스크립트가 필요하다. HDEL이 실패해도 인덱스 field는 TTL로 사라지고, 조회 쪽은 본문 없는 인덱스를 만료로 처리한다.

### Lua 계약

```text
KEYS[1]    예약 키
KEYS[2..]  객차 Hash (중복 없이, 처음 등장 순서)
ARGV       reservationId, ttlSec(>=1), keyExpireAt, json, depOrder, arrOrder, "seatId:carKeyIndex"...

반환  {1}                               성공
      {0, seatId, sectionIndex, "R"}    다른 예약이 점유 중  → SEAT_CONFLICT_WITH_RESERVATION (409)
      {0, seatId, sectionIndex, "B"}    이미 예매됨          → SEAT_CONFLICT_WITH_BOOKING (409)
      {0, seatId, sectionIndex, "X"}    알 수 없는 값 형식   → SEAT_OCCUPANCY_CORRUPTED (500)
```

같은 `reservationId`로 다시 실행하면 자기 점유는 충돌로 보지 않는다. API는 한 객차의 좌석만 받으므로 객차 Hash는 `KEYS[2]` 하나지만, 스크립트는 여러 객차를 받을 수 있게 되어 있다.

## 5. 결제 중 좌석 보호와 해제

결제 결과를 모르는 동안에는 좌석 field의 만료를 없애 예약 TTL이 좌석을 가져가지 못하게 막는다. 보호 대상은 좌석 field뿐이고 예약 본문과 회원 인덱스는 원래 10분 규칙을 그대로 따른다. 마이페이지가 10분 규칙을 따라야 하기 때문이다.

| 스크립트 | 호출 시점 | 동작 |
|---|---|---|
| `reservation_payment_hold.lua` | Toss 응답이 결과 불명일 때, Recovery Worker가 attempt를 처리할 때 | 자기 `R:` field의 만료를 없앤다(HPERSIST). 비어 있는 field에는 `R:`을 다시 쓰고 만료를 없앤다. 다른 값이 하나라도 있으면 아무것도 쓰지 않고 충돌을 돌려준다 |
| `reservation_payment_release.lua` | 확정 실패를 기록할 때 | 자기 `R:` field의 만료를 보호 이전 상태로 되돌린다. 주문 표시가 살아 있으면 표시의 남은 TTL을, 없으면 예약 본문의 남은 TTL을 쓴다. 둘 다 없으면 field를 삭제한다 |

해제는 좌석을 버리는 것이 아니라 보호 이전으로 되돌리는 것이다. 카드가 거절되면 사용자는 다른 카드로 다시 결제하고 그동안 좌석이 남아 있어야 한다. 그래서 `releaseHold`는 자기 `R:`이 아닌 field를 건드리지 않는다. 다른 예약의 `R:`은 남의 좌석이고, 자기 `B:`는 이미 확정된 예매라 되돌릴 대상이 아니다.

두 스크립트 모두 예약 본문을 쓰지 않는다. `releaseHold`는 본문의 남은 TTL을 읽기만 한다. 그 값은 `Reservation.expiresAt`과 같은 시각이다.

```text
reservation_payment_hold.lua
KEYS[1..]  객차 Hash (중복 없이, 처음 등장 순서)
ARGV       reservationId, keyExpireAt, depOrder, arrOrder, "seatId:carKeyIndex"...
반환       {1} 또는 {0, seatId, sectionIndex, "R"|"B"|"X"}   ("X" → SEAT_OCCUPANCY_CORRUPTED)

reservation_payment_release.lua
KEYS[1]    예약 본문 키
KEYS[2]    주문 표시 키
KEYS[3..]  객차 Hash (중복 없이, 처음 등장 순서)
ARGV       reservationId, depOrder, arrOrder, "seatId:carKeyIndex"...
반환       {restoredCount, deletedCount}
```

만료를 읽을 때는 `TTL`, field 만료를 걸 때는 `HEXPIRE`를 쓴다. 둘 다 상대 초다. 절대 시각 `EXPIREAT`은 운행일 기준이 필요한 객차 키 만료에만 쓴다.

주문 표시 키는 계획 B가 쓰기 시작한다. 그전까지 `releaseHold`는 항상 예약 본문의 남은 TTL로 되돌린다.

보호 해제가 건너뛴 field는 `hold`가 이미 만료를 없앴기 때문에 **만료도 주인도 없는 상태로 남아 객차 키의 운행일 만료까지 팔리지 않는다.** 건너뛰는 이유가 둘이라 실패로 단정할 수 없다. 사이에 예매로 전환된 `B:` field는 정상적으로 건너뛰는 것이고, 오염된 값은 좌석을 묶는 것이다. 그래서 `releaseHold`는 예외를 던지지 않고 `restored + deleted`가 기대 수보다 적을 때 경고 로그만 남긴다.

## 6. 조회

결제가 예약을 읽을 때는 `member:{memberNo}:reservations`에서 HMGET으로 운행 ID를 얻고 예약 키를 MGET한다. 인덱스에 없는 예약은 만료된 것으로 본다. 다른 회원의 예약은 요청자의 인덱스에 없으므로 같은 이유로 `RESERVATION_EXPIRED`가 된다. `RESERVATION_ACCESS_DENIED`는 인덱스와 본문의 회원번호가 어긋난 경우에만 남는다.

내 예약 목록(`GET /api/v1/reservations`)은 인덱스를 HGETALL로 읽고 같은 방식으로 본문을 MGET한다. 본문이 먼저 만료된 인덱스는 결과에서 빠지고, 생성 시각 순으로 정렬한다.

## 6-1. 삭제

`DELETE /api/v1/reservations/{reservationId}`는 자기 예약의 점유만 즉시 해제한다.

1. 회원 인덱스에서 운행 ID를 찾는다. 없으면 이미 만료·삭제된 예약(또는 남의 예약)이라 아무것도 하지 않고 성공한다.
2. 본문이 있으면 소유자를 검증하고 `reservation_delete.lua`로 점유 해제와 본문 삭제를 원자적으로 처리한다. 본문이 먼저 만료됐다면 이 단계를 건너뛴다.
3. 회원 인덱스를 HDEL한다. 실패해도 field TTL로 사라지고 조회가 본문 없는 인덱스를 만료로 처리한다.

점유 해제를 먼저, 인덱스를 나중에 지우는 이유는 반대 순서면 점유가 남은 채 인덱스만 사라져 다시 지울 수 없기 때문이다.

```text
KEYS[1]    예약 키
KEYS[2..]  객차 Hash (중복 없이, 처음 등장 순서)
ARGV       reservationId, depOrder, arrOrder, "seatId:carKeyIndex"...

반환  {해제한 field 수}
```

값이 정확히 `R:{reservationId}`인 field만 HDEL한다. 다른 예약의 `R:`과 예매 `B:`는 건드리지 않는다. 스크립트 실행이 실패하면 `SEAT_OCCUPANCY_RELEASE_FAILED`(500)다. 결제 진행 중인 예약의 삭제 보호는 이 범위 밖이며 결제 소유권 작업(#280, #259)에서 다룬다.

## 6-2. 예매 삭제

`DELETE /api/v1/bookings`는 본인 예매만 지우고, 그 예매가 점유한 `B:` field를 해제한다. `B:`에는 만료가 없어서 지워 주지 않으면 객차 키가 만료되는 운행일+2일까지 좌석이 남는다. 그동안 예약 생성은 `SEAT_CONFLICT_WITH_BOOKING`으로 거절되고 검색 잔여석에서도 팔린 좌석으로 계산된다.

해제는 **DB 삭제가 커밋된 뒤에** 일어나야 한다. 커밋 전에 지우면 삭제가 롤백됐을 때 예매는 살아 있는데 좌석이 비어, 다른 사용자가 같은 좌석을 예약할 수 있다. 그래서 Redis를 요청 안에서 건드리지 않고 Outbox에 맡긴다.

1. 한 트랜잭션에서 소유자를 검증하고, 좌석 예매 행에서 해제에 필요한 좌석과 객차를 읽어 `BOOKING_SEAT_RELEASE_REQUIRED` outbox 행을 쓰고, 예매를 삭제한다. 세 작업이 같은 커밋이라 해제 요청이 유실되지 않는다.
2. API는 바로 성공으로 응답한다. 사용자가 원한 삭제는 끝났고 내 예매 목록은 DB만 보므로 즉시 사라진다.
3. `PaymentOutboxWorker`가 그 행을 집어 `BookingSeatReleaseProcessor`에 넘기고 `booking_seat_release.lua`를 실행한다. 폴링 주기는 5초다.

Redis가 죽어 있으면 Outbox의 지수 백오프 재시도를 타고, 최대 재시도를 넘기면 `FAILED`와 `payment.outbox.failed` 카운터로 올라간다.

```text
KEYS[1..]  객차 Hash (중복 없이, 처음 등장 순서)
ARGV       bookingId, depOrder, arrOrder, "seatId:carKeyIndex"...

반환  {해제한 field 수}
```

값이 정확히 `B:{bookingId}`인 field만 HDEL한다. 다른 예매의 `B:`와 예약 `R:`은 건드리지 않는다. 예약 본문은 예매로 전환될 때 이미 사라졌으므로 지울 것이 없다. 해제 수가 0이어도 실패가 아니다 — 이미 해제됐거나, 예매 전환이 아직 일어나지 않은 예매를 지운 경우다. 다만 후자는 좌석이 풀렸다는 뜻이 아니다. 아래 경합 2를 함께 읽어야 한다. 스크립트 실행이 실패하면 `SEAT_OCCUPANCY_RELEASE_FAILED`(500)이고 Outbox가 재시도한다.

좌석 예매와 승차권은 DB의 ON DELETE CASCADE로 함께 지워지지만 결제는 `PAID`로 남는다. 예매 삭제를 취소 흐름으로 대체할지는 #259에서 정한다.

**남은 경합 둘.** `BOOKING_CONFIRMED`가 아직 처리되지 않은 예매를 지우면 두 가지가 남을 수 있다. 둘 다 현재 막지 않는다.

1. **`B:`가 해제 뒤에 쓰인다.** `BookingConfirmedProcessor`의 `isBooked` 가드는 예매 행이 없으면 `B:`를 쓰지 않고 넘어가지만, 그 가드를 통과한 직후에 삭제가 커밋되면 해제는 0건으로 끝나고 그 뒤에 `B:`가 쓰인다. 창이 좁다.
2. **예약 `R:` field가 남는다.** `isBooked` 가드가 걸린 항목은 `discardReservation`으로 가고, 그 경로는 예약 본문과 회원 인덱스만 지우고 **좌석 field는 손대지 않는다**. 가드가 `B:` 쓰기만 막고 좌석을 풀어 주지는 않는다. 그래서 예약 본문도 회원 인덱스도 예매도 없는데 좌석 field만 `R:{reservationId}`로 남는다. 가리키는 대상이 없는 고아 점유다. 새 해제 스크립트는 `B:{bookingId}` 비교 삭제라 이 field를 지울 수 없다.

**두 번째의 심각도는 `reservation_payment_hold.lua`의 배선 여부로 갈린다.** 지금은 그 스크립트를 호출하는 운영 코드가 없다(`hold`와 `releaseHold`의 호출처는 `SeatOccupancyRepositoryTest`뿐이며, #270의 Recovery Worker 본체가 아직 없다). 그래서 남은 `R:` field에는 예약 TTL이 그대로 있고 10분 안에 저절로 사라진다. 좌석이 스스로 돌아오므로 현재 영향은 작다.

**#270이 `hold`를 배선하면 달라진다.** 보호가 걸렸던 예약의 field는 HPERSIST로 만료가 제거되므로(§5), 주인도 만료도 없는 `R:`가 객차 키의 운행일 만료까지 남아 좌석이 묶인다. 자가 회복 경로가 없다 — TTL이 없고, 예약 본문과 회원 인덱스가 사라져 예약 삭제 API로도 지울 수 없다. #298이 고치려던 증상이 다른 값으로 재현된다.

**대응은 #270에서 한다.** 위험을 만드는 작업이 #270이고, 고칠 자리도 그쪽이다. `BookingConfirmedPayload.Entry`가 `reservationId`와 좌석, 구간을 이미 들고 있으므로, `discardReservation` 분기에서 기존 `reservation_delete.lua`로 자기 `R:`를 함께 해제하면 된다. 스키마 변경이 필요 없다.

## 7. 구현 규칙

- 새 Redis 코드는 `StringRedisTemplate`을 쓴다. `customStringRedisTemplate`은 hash serializer가 JDK 직렬화라 Hash field가 바이트로 깨진다.
- 값 JSON은 `RedisJsonConverter`로 읽고 쓴다. Batch의 `TrainCacheJsonConverter`와 같은 설정이다.
- 운행 키 만료는 운행일 기준 절대 시각이다. 적재 시점 기준 상대 TTL을 쓰지 않는다.
- `TrainCacheKey.DEFAULT_RETENTION_DAYS`(2)와 Batch의 `train.cache.retention-days`는 같은 값이어야 한다.

