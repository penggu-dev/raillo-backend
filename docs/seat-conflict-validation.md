# Seat Conflict Validation (4-Layer Defense)

좌석 충돌을 막기 위한 4단계 방어 구조.

## Overview

| Layer | When | What | How |
|-------|------|------|-----|
| **Layer 1** | `createPendingBooking` | PendingBooking vs PendingBooking | Redis Lua script (atomic section check) |
| **Layer 2** | `createPendingBooking` | PendingBooking vs SeatBooking (DB) | `BookingValidator.validateSeatConflicts(trainScheduleId, ...)` — SQL section overlap query (Fail Fast) |
| **Layer 3** | `preparePayment` | PendingBooking vs SeatBooking (DB) | `BookingValidator.validateSeatConflicts(pendingBookings)` — bulk ScheduleStop query + SQL section overlap (re-validation) |
| **Layer 4** | `confirmPayment` | PendingBooking TTL expiry | `validateAndGetPendingBookings()` |

## SQL Section Overlap Condition (Layer 2 & 3)

```sql
-- SeatBookingRepository.findConflictingSeatBookings()
sb.departureStopOrder < :arrivalStopOrder AND sb.arrivalStopOrder > :departureStopOrder
```

이 구간 중첩(interval overlap) 조건은 요청 범위와 교차하는 어떤 SeatBooking이든 감지한다.

## Layer 별 책임

### Layer 1 — Redis Lua (PendingBooking 간 충돌)
다른 사용자의 임시 점유와 충돌하는지 원자적으로 검사한다. `seat_hold.lua`가 SISMEMBER 기반으로 section 단위 충돌을 검출한다. 자세한 흐름은 [seat-hold-architecture.md](./seat-hold-architecture.md) 참조.

### Layer 2 — SQL Fail Fast (확정 예매와 충돌)
`createPendingBooking` 진입 시 Lua 스크립트 실행 **전에** SQL로 확정 예매와의 충돌을 검사한다. 실패 시 Redis 쓰기를 시작하지 않고 즉시 반환한다.

### Layer 3 — SQL Re-validation (결제 준비 시)
`preparePayment` 시점에 다시 한 번 확정 예매와의 충돌을 검사한다. PendingBooking이 살아 있던 10분 동안 다른 결제가 확정되었을 가능성을 차단한다. 여러 PendingBooking을 한꺼번에 검증하기 위해 ScheduleStop을 bulk 조회한다.

### Layer 4 — TTL Expiry Check
`confirmPayment`에서 PendingBooking 자체가 만료되었는지 확인한다. Redis TTL이 지났다면 결제 승인을 거부한다.

## 왜 4단계인가

- Layer 1만으로는 확정 예매와의 충돌을 막을 수 없다 (Redis는 임시 점유만 안다).
- Layer 2만으로는 동시 요청을 막을 수 없다 (SQL 시점과 Redis 쓰기 시점 사이 race가 발생).
- Layer 3은 결제 직전 재검증으로 long-living PendingBooking을 보호한다.
- Layer 4는 TTL 경계 케이스를 처리한다.
