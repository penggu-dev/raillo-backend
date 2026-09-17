# 예약(Reservation) Redis 스키마

예약 생성이 DB 없이 Redis(Valkey 9)만으로 조회·검증·원자적 점유를 끝내기 위한 키 계약이다. 기준정보 읽기 쪽 계약은 [train-cache-schema.md](./train-cache-schema.md)를 본다.

키 포맷은 `raillo-domain`의 `booking/cache` 패키지가 단일 원본이다. Batch가 나중에 예매 좌석을 다시 채울 때도 같은 클래스를 쓴다.

- 키: `ReservationCacheKey`
- 점유 값: `SeatOccupancyValue`
- 예약 본문: `booking/domain/Reservation` (JSON)

## 1. 키 목록

| 키 | 타입 | 만료 | 내용 |
|---|---|---|---|
| `{schedule:{sid}}:car:{carId}:seats` | Hash | 키: 운행일 기준 EXPIREAT, 예약 field: HEXPIRE | 객차 하나의 좌석 점유 상태 |
| `{schedule:{sid}}:reservation:{rid}` | String | EX = 예약 TTL | 예약 본문 JSON |
| `member:{memberNo}:reservations` | Hash | field별 HEXPIRE = 예약 TTL | 회원별 예약 인덱스. field rid → sid |

운행 단위 키는 기준정보 캐시와 같은 `{schedule:id}` hash tag를 써서 Redis Cluster에서 한 운행의 점유·예약·기준정보가 같은 slot에 놓인다. 회원 인덱스는 운행과 무관하므로 별도 slot이며 Lua 밖에서 다룬다.

## 2. 좌석 점유 Hash

```text
{schedule:1001}:car:231:seats
  field  46456:0   value  R:RV20260917120000A1B2C3    예약 점유 (예약 TTL만큼 HEXPIRE)
  field  46456:1   value  R:RV20260917120000A1B2C3
  field  46457:2   value  B:77                        예매 점유 (만료 없음)
```

- field는 `{seatId}:{sectionIndex}`. 구간 index는 정차 순서 i에서 i+1로 가는 한 칸이며 값은 i다. 출발 stopOrder d, 도착 stopOrder a인 요청은 d..a-1 구간 field를 점유한다.
- 값은 `R:{reservationId}`(예약) 또는 `B:{bookingId}`(예매)다. 그 외 형식은 데이터 오염으로 보고 `SEAT_OCCUPANCY_SCRIPT_ERROR`를 낸다.
- 키는 점유가 처음 생길 때 만들어지고, TTL이 없을 때만 `TrainCacheKey.expireAtEpochSecond(운행일)`로 EXPIREAT을 건다. 빈 열차는 키가 없다.
- 예약 field는 HEXPIRE로 예약과 함께 사라진다. 별도 정리 작업이나 인덱스가 필요 없다. 필드 단위 만료는 Redis 7.4, Valkey 9.0부터 지원한다.
- 예매 점유(`B:`) 기록은 결제 확정 PR에서 붙는다. 그 전까지는 테스트 헬퍼만 이 값을 쓴다.

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

1. 요청 검증: 승객 수 = 좌석 수, 좌석 중복 없음, 출발역 ≠ 도착역.
2. 기준정보 조회 (`TrainCacheRepository`, 파이프라인 1회): `{schedule}:info`, `{schedule}:stops`의 `st:{출발역}`·`st:{도착역}`, `train:seat:{id}` MGET, `train:fare` HGET. 하나라도 없으면 404.
3. 운행 검증 (`ReservationValidator`): 운행 취소, 정차 순서, 출발 5분 전 마감, 객차 타입 단일(객차가 달라도 됨).
4. 운임(`FareCalculator`, 캐시 운임)과 TTL(`ReservationService.calculateTtl`): min(10분, 마감까지 남은 시간), 정수 초 올림, 최소 1초.
5. 회원 인덱스 등록: HSETEX(`putAndExpire`)로 값과 field TTL을 한 번에.
6. `reservation_create.lua`: 요청 field를 HMGET해 자기 예약이 아닌 값이 하나라도 있으면 아무것도 쓰지 않고 충돌을 돌려준다. 없으면 HSET → HEXPIRE → (TTL 없을 때) EXPIREAT → SET 예약 EX.
7. 충돌·오류 시 회원 인덱스를 HDEL로 되돌린다.

인덱스를 Lua보다 먼저 쓰는 이유는 보상이 HDEL 하나로 끝나기 때문이다. 반대 순서면 점유를 푸는 스크립트가 필요하다. HDEL이 실패해도 인덱스 field는 TTL로 사라지고, 조회 쪽은 본문 없는 인덱스를 만료로 처리한다.

### Lua 계약

```text
KEYS[1]    예약 키
KEYS[2..]  객차 Hash (중복 없이, 처음 등장 순서)
ARGV       rid, ttlSec(>=1), keyExpireAt, json, depOrder, arrOrder, "seatId:carKeyIndex"...

반환  {1}                               성공
      {0, seatId, sectionIndex, "R"}    다른 예약이 점유 중  → SEAT_CONFLICT_WITH_RESERVATION (409)
      {0, seatId, sectionIndex, "B"}    이미 예매됨          → SEAT_CONFLICT_WITH_BOOKING (409)
      {0, seatId, sectionIndex, "X"}    알 수 없는 값 형식   → SEAT_OCCUPANCY_SCRIPT_ERROR (500)
```

같은 `rid`로 다시 실행하면 자기 점유는 충돌로 보지 않는다.

## 5. 조회

결제가 예약을 읽을 때는 `member:{memberNo}:reservations`에서 HMGET으로 운행 ID를 얻고 예약 키를 MGET한다. 인덱스에 없는 예약은 만료된 것으로 본다. 다른 회원의 예약은 요청자의 인덱스에 없으므로 같은 이유로 `RESERVATION_EXPIRED`가 된다. `RESERVATION_ACCESS_DENIED`는 인덱스와 본문의 회원번호가 어긋난 경우에만 남는다.

## 6. 구현 규칙

- 새 Redis 코드는 `StringRedisTemplate`을 쓴다. `customStringRedisTemplate`은 hash serializer가 JDK 직렬화라 Hash field가 바이트로 깨진다.
- 값 JSON은 `RedisJsonConverter`로 읽고 쓴다. Batch의 `TrainCacheJsonConverter`와 같은 설정이다.
- 운행 키 만료는 운행일 기준 절대 시각이다. 적재 시점 기준 상대 TTL을 쓰지 않는다.
- `TrainCacheKey.DEFAULT_RETENTION_DAYS`(2)와 Batch의 `train.cache.retention-days`는 같은 값이어야 한다.

