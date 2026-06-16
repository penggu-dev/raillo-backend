# Seat Hold Architecture (Concurrency Control)

좌석 동시 선점 충돌을 막기 위한 Redis Lua 스크립트 기반 아키텍처.

## Why Lua Scripts?

```
Without Lua (Race Condition):
  UserA: Check seat → OK       UserB: Check seat → OK
  UserA: Save hold             UserB: Save hold
  → Both succeed! Conflict!

With Lua (Atomic):
  UserA: [Check + Save] atomic → OK
  UserB: [Check + Save] atomic → CONFLICT (seat already held)
```

Lua 스크립트는 Redis에서 단일 명령으로 원자적 실행을 보장한다.

## Section-Based Conflict Detection

좌석은 "구간(section)" 단위로 예약된다 — 전체 경로가 아닌 정차역 간 구간이다. 겹치지 않는 구간이면 같은 좌석을 여러 승객이 공유할 수 있다.

```
Train stops:    [Seoul] → [Daejeon] → [Dongdaegu] → [Busan]
stopOrder:         0          1            2           3
Sections:           sec 0-1      sec 1-2       sec 2-3

Booking A: Seoul → Dongdaegu = sections {"0-1", "1-2"}
Booking B: Dongdaegu → Busan = sections {"2-3"}
→ No conflict! Same seat, different sections.

Booking C: Daejeon → Busan = sections {"1-2", "2-3"}
→ Conflict with Booking A on section "1-2"
```

## Redis Key Structure

| Key Pattern | Type | TTL | Description |
|-------------|------|-----|-------------|
| `{seat:scheduleId:seatId}:hold:pendingId` | Set | 10min | Temporary hold sections |
| `{seat:scheduleId:seatId}:holds` | Set | 10min | Index of active hold IDs |
| `{schedule:scheduleId}:traincar:carId:holding-seats` | Sorted Set | 20min | Hold Index for seat search |

Example for seat 12 on schedule 1785:
```
{seat:1785:12}:hold:pending_abc = {"0-1", "1-2"}  # UserA holding Seoul→Dongdaegu
{seat:1785:12}:holds = {"pending_abc"}             # Active hold index
{schedule:1785}:traincar:231:holding-seats = {12:0-1 => 1712345678}  # Hold Index (seatId:section -> expiryTime)
```

> **Note**: 확정 예매(SeatBooking)는 Redis가 아닌 MySQL에 저장된다. 과거의 sold 키 구조는 제거되었고, DB SeatBooking 검증이 확정 예매의 단일 진실 공급원(SoT)이다.

## seat_hold.lua Flow

```
Input: holdKey, holdsKey, ttl, pendingBookingId, seatId, scheduleId, trainCarId, sections

1. HOLD Conflict Check (other users' temporary holds)
   holdIds = SMEMBERS {seat:..}:holds
   for each holdId (except self):
     if otherHoldKey expired → SREM (Lazy Cleanup)
     else for each section:
       SISMEMBER {seat:..}:hold:holdId section
       → if exists: return {0, "CONFLICT_WITH_HOLD", section}

2. Create Hold (if no conflicts)
   SADD {seat:..}:hold:pendingId sections...
   EXPIRE {seat:..}:hold:pendingId ttl
   SADD {seat:..}:holds pendingId
   EXPIRE {seat:..}:holds ttl

3. Update Hold Index (Sorted Set for train search)
   expiryTime = currentTime + ttl
   for each section:
     ZADD {schedule:..}:traincar:..:holding-seats expiryTime "seatId:section"
   EXPIRE holdIndexKey ttl*2
   return {1, "HOLD_SUCCESS"}
```

## seat_release.lua Flow

```
Input: holdKey, holdsKey, pendingBookingId, seatId, scheduleId, trainCarId, sections

1. DEL holdKey (seat hold data)
2. SREM holdsKey pendingBookingId (holds index cleanup)
3. For each section:
     ZREM {schedule:..}:traincar:..:holding-seats "seatId:section" (Hold Index cleanup)
4. Return {1, "RELEASE_SUCCESS"} or {1, "ALREADY_RELEASED"}
```

## get_hold_seats_count.lua Flow

```
Input: KEYS = Hold Index keys (per trainCar), ARGV = currentTime + search sections

1. For each Hold Index key (one per trainCar):
     ZRANGEBYSCORE key currentTime +inf (get non-expired members)
     For each member "seatId:section":
       if section in searchSections → add seatId to uniqueSeats set
2. Return count of unique seatIds (deduplicated across trainCars)
```

## Conflict Types

| Return Value | Meaning | User Action |
|--------------|---------|-------------|
| `{1, "HOLD_SUCCESS"}` | Hold created successfully | Proceed to payment |
| `{0, "CONFLICT_WITH_HOLD", "1-2"}` | Another user is paying | Retry after a few minutes |

확정 예매와의 충돌(SeatBooking 충돌)은 Lua 스크립트 실행 전 `BookingValidator.validateSeatConflicts()`가 SQL 구간 중첩 쿼리로 감지한다 (Layer 2: Fail Fast).
구간 중첩 조건: `departureStopOrder < :arrivalStopOrder AND arrivalStopOrder > :departureStopOrder`

## Related Classes

- `SeatHoldRepository` — Lua 스크립트 실행 (hold, release, count)
- `SeatHoldService` — Hold/Release/Count 비즈니스 로직
- `SeatHoldKeyGenerator` — Redis 키, 섹션 문자열, Hold Index 키 생성
- `SeatHoldResult` — Lua 반환값 파싱
- `PendingBookingFacade` — Hold → Save → Release 흐름 조율
- `RedisScriptConfig` — 3개 Lua 스크립트 Bean 등록

## Why pendingBookingId in Hold Keys?

`pendingBookingId`는 Seat Hold와 PendingBooking을 하나의 예약 단위로 묶는다.

```
pendingBookingId = "pending_abc123"
        │
        ├─────────────────────────────────┐
        ▼                                 ▼
┌─────────────────────────┐    ┌─────────────────────────┐
│ {seat:..}:hold:         │    │ pending:                │
│   pending_abc123        │    │   pending_abc123        │
│ = {"0-1", "1-2"}        │    │ = {scheduleId, seats..} │
│ (Seat Lock)             │    │ (Booking Data)          │
└─────────────────────────┘    └─────────────────────────┘
```

| Purpose | Description |
|---------|-------------|
| **Confirm** | 결제 성공 시 어떤 Hold를 해제할지 식별 |
| **Release** | 취소 시 어떤 Hold를 삭제할지 식별 |
| **Idempotency** | 재시도 시 자기 충돌 체크 스킵 (`holdId ~= pendingBookingId`) |
| **Multi-user** | 동일 좌석 다른 구간을 점유한 사용자 구분 |

## Lazy Cleanup in HOLD Conflict Check

HOLD 충돌 체크 시 만료된 Hold는 게으르게(Lazy) 정리한다:

```lua
if redis.call("EXISTS", otherHoldKey) == 0 then
    -- Hold key expired (TTL), but ID still in holds index
    redis.call("SREM", holdsKey, holdId)  -- Clean up zombie entry
    -- Skip conflict check (no data to check)
else
    -- Valid hold, proceed with conflict check
end
```

이유:
1. `hold:pending_xxx` 키는 10분 TTL로 자동 만료된다
2. `holds` 인덱스는 다른 hold가 TTL을 갱신하면 만료된 ID를 그대로 가지고 있을 수 있다
3. 다음 hold 시도가 stale entry를 발견하고 제거한다 ("lazy" = 접근 시점에 정리)

## Train Search Hold Integration

열차 검색은 배치 쿼리와 Lua 기반 카운팅으로 점유 좌석을 좌석 가용성 계산에 반영한다.

### Architecture Flow

```
TrainSearchFacade.processTrainSearchResults()
  │
  ├─ 1. Batch fetch: TrainSeatInfoBatch (total seats per CarType)
  ├─ 2. Batch fetch: overlapping SeatBookings (confirmed bookings)
  ├─ 3. Batch fetch: TrainCarIdsBatch (trainCarIds grouped by schedule + CarType)
  │
  └─ For each train:
       ├─ totalSeats from TrainSeatInfoBatch
       ├─ seatBooking count from overlapping bookings
       ├─ holdSeats: for each CarType → SeatHoldService.calculateHoldSeatByCarType()
       │                                 → Lua: get_hold_seats_count.lua
       └─ SeatAvailabilityCalculator: remaining = total - seatBooking - hold
```

### Key Classes

- `TrainSearchFacade` — 배치 쿼리와 열차별 처리 조율
- `SeatAvailabilityCalculator` — 잔여 좌석 계산: `Math.max(0, total - seatBooking - hold)`
- `TrainCarIdsBatch` — `Map<scheduleId, Map<CarType, List<trainCarId>>>` 캐싱 DTO
- `TrainScheduleQueryRepository.findTrainCarIdsBatch()` — 모든 스케줄에 대한 단일 QueryDSL JOIN

### Batch Query Strategy

모든 데이터(totalSeats, SeatBookings, trainCarIds)는 열차별 처리 전에 일괄 조회한다. N+1 쿼리를 제거한다.
