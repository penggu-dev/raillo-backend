# Seat Conflict Validation

좌석 충돌을 막는 방어 구조.

## 단일 방어선

좌석 충돌 판정은 **`seat_occupancy` 의 유니크 제약 한 곳**에서만 이뤄진다.

```sql
UNIQUE KEY uk_seat_occupancy_section (train_schedule_id, seat_id, section_order)
```

예약(HELD)과 확정 예매(CONFIRMED)가 같은 테이블에 있으므로, 두 종류의 충돌이 하나의 제약으로 동시에 막힌다.

| 시점 | 무엇을 하는가 |
|------|--------------|
| `ReservationFacade.createReservation` | ① `BookingValidator.validateSeatConflicts` — 사전 조회로 빠른 실패<br>② `SeatOccupancyService.hold` — **유니크 제약이 최종 판정** |
| `PaymentFacade.preparePayment` | 좌석 재검증 없음. 예약 만료 여부만 확인 |
| `PaymentFacade.confirmPayment` | 예약 행 `FOR UPDATE` + 확정 전이의 affected rows 검사 |

## 왜 재검증이 없는가

예약 시점에 `seat_occupancy` 가 좌석을 점유했고 그 점유는 만료 전까지 유지된다. 결제 준비 단계에서 좌석 충돌을 다시 검사하면 **자기 자신의 점유를 충돌로 판정**하게 된다.

Redis 시절에는 임시 점유(Redis)와 확정 예매(MySQL)가 분리되어 있어 결제 직전 재검증이 필요했지만, 두 저장소가 하나로 합쳐지면서 불필요해졌다.

## 사전 검증의 역할

`validateSeatConflicts`는 **빠른 실패를 위한 보조 수단**이지 방어선이 아니다. 이 검증과 실제 INSERT 사이에는 여전히 race window가 있고, 그 창은 유니크 제약이 닫는다.

동시성 테스트(`ReservationConcurrencyTest`)가 이를 검증한다 — 100개 스레드가 같은 좌석·같은 구간을 동시에 요청해도 정확히 1건만 성공한다.

## 만료 정리와의 경합

결제 승인 도중 만료 정리가 같은 행을 지울 수 있다. 두 겹으로 막는다.

1. `ReservationRepository.findAllByReservationCodeInForUpdate` — 예약 행을 잠근다 (보조)
2. `SeatOccupancyService.confirm` — 전이된 행 수가 기대치(좌석 수 × 구간 수)와 다르면 `RESERVATION_EXPIRED` 로 롤백 (**실제 안전장치**)

affected rows 검사가 벤더 무관하게 동작하므로 이쪽이 본체다.

## 관련 문서

- 구간 전개 모델, 만료 처리, 잔여석 집계 → [seat-occupancy-architecture.md](./seat-occupancy-architecture.md)
