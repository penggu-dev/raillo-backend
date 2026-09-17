# Seat Conflict Validation

예약 생성부터 결제 확정까지 좌석 충돌을 막는 방어 구조.

| Layer | When | What | How |
|-------|------|------|-----|
| **Layer 1** | `createReservation` | 요청·운행 규칙 | `ReservationValidator` — 기준정보 캐시 값만 검사 |
| **Layer 2** | `createReservation` | 임시 점유 vs 확정 판매 | `reservation_create.lua` — 객차 Hash의 `H:`/`B:` field를 원자적으로 검사하고 점유 |
| **Layer 3** | `preparePayment` | 예약 vs 확정 예매(DB) | `BookingValidator.validateSeatConflicts` — SQL 구간 중첩 재검증 |
| **Layer 4** | 만료 | 예약 TTL | 예약 키 EX, Hold field HEXPIRE, 회원 인덱스 field HEXPIRE가 같은 TTL로 함께 사라진다 |

## Layer 2 — 임시 점유와 확정 판매를 한 번에 검사한다

객차 점유 Hash에는 임시 점유(`H:{reservationId}`)와 확정 판매(`B:{bookingId}`)가 같은 field 형식으로 들어간다. Lua가 요청 구간의 field를 HMGET 한 번으로 읽어 두 경우를 모두 막고, 충돌이 없을 때만 점유를 쓴다. 검사와 쓰기가 한 스크립트 안에서 끝나므로 동시 요청 사이에 race가 없다.

확정 판매 기록은 결제 확정 시 `H:` → `B:` 전환으로 붙고, Batch 복구 Step이 `SeatBooking`으로 다시 채운다.

## Layer 3 — 결제 직전 DB 재검증을 유지하는 이유

Redis는 예매의 진실 공급원이 아니다. 캐시가 유실된 직후 복구 전까지 `B:` 값이 비어 있을 수 있으므로, 결제 직전에 DB로 한 번 더 확인한다. 예약 생성 경로에는 없고 결제 경로에서 쿼리 한 번이라 비용이 작다.

```sql
-- SeatBookingRepository.findOverlappingSeatBookings()
sb.departureStopOrder < :arrivalStopOrder AND sb.arrivalStopOrder > :departureStopOrder
```

## Layer 4 — TTL

예약 본문, Hold field, 회원 인덱스 field가 같은 TTL을 가지므로 예약이 만료되면 세 곳이 함께 사라진다. 별도 정리 작업이나 인덱스가 필요 없다.
