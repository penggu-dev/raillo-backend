# Seat Occupancy Architecture (Concurrency Control)

좌석 충돌 판정과 잔여석 계산의 단일 진실 공급원인 `seat_occupancy` 테이블 구조.

## 핵심 아이디어

좌석은 "구간(section)" 단위로 점유된다 — 전체 경로가 아닌 정차역 간 구간이다. 겹치지 않는 구간이면 같은 좌석을 여러 승객이 공유할 수 있다.

```
Train stops:    [서울] → [대전] → [동대구] → [부산]
stopOrder:        0        1         2         3
sectionOrder:        0         1         2

예약 A: 서울 → 동대구 = sectionOrder {0, 1}
예약 B: 동대구 → 부산 = sectionOrder {2}
→ 충돌 없음. 같은 좌석, 다른 구간.

예약 C: 대전 → 부산 = sectionOrder {1, 2}
→ 예약 A와 sectionOrder 1에서 충돌
```

**구간을 단위 구간으로 전개하면 "구간 겹침" 판정이 부등호 비교에서 유니크 인덱스의 동등 비교로 환원된다.** 이것이 이 설계의 전부다.

```sql
UNIQUE KEY uk_seat_occupancy_section (train_schedule_id, seat_id, section_order)
```

출발 `dep` ~ 도착 `arr` 예약은 `section_order ∈ [dep, arr-1]` 로 전개되어 구간 수만큼 행이 생성된다.

## 예약과 예매가 같은 테이블을 공유한다

| status | 의미 | expires_at | 소유자 |
|--------|------|-----------|--------|
| `HELD` | 예약이 임시 점유 | 예약 만료 시각 | `reservation_id` |
| `CONFIRMED` | 결제 완료로 확정 | `9999-12-31` (센티넬) | `reservation_id` + `booking_id` |

결제 승인 시 **행을 옮기지 않고 상태만 전이**시킨다. 따라서 점유가 끊기는 순간이 없다.

```
예약 생성    Reservation(HELD) + ReservationSeat + SeatOccupancy(HELD)   [1 트랜잭션]
결제 준비    만료 여부만 확인 — 좌석 재검증 불필요 (점유가 이미 살아있음)
결제 승인    UPDATE seat_occupancy SET status=CONFIRMED, booking_id=?,
                    expires_at='9999-12-31' WHERE reservation_id=? AND status='HELD'
예약 취소    DELETE FROM seat_occupancy WHERE reservation_id=?
예매 취소    DELETE FROM seat_occupancy WHERE booking_id=?  (FK CASCADE로도 정리)
```

> **해제는 반드시 물리 삭제다.** `status='RELEASED'` 같은 값을 남기면 유니크 인덱스가 좌석을 영구 점유한다. 그래서 `SeatOccupancyStatus`에는 HELD/CONFIRMED 두 값만 존재한다. 이력은 `Reservation.status`와 `Booking`에 남는다.

## 만료 처리 (Redis TTL 대체)

`expires_at` 센티넬 덕분에 모든 조회 필터가 한 조건으로 통일된다.

```sql
WHERE expires_at > NOW()   -- status 조건 불필요
```

만료된 HELD 행은 **조회에서는 걸러지지만 유니크 인덱스에는 살아 있어 재점유를 막는다.** 그래서 점유 INSERT 직전에 선삭제가 필수다.

```
SeatOccupancyService.hold()
  1. deleteExpiredInSections(...)          ← 만료 행 선삭제 (없으면 재점유 실패)
  2. 좌석 × 구간 전개 후 (seatId, sectionOrder) 오름차순 정렬
  3. saveAll → flush                        ← 유니크 위반이 곧 좌석 충돌
  4. DataIntegrityViolationException → SEAT_ALREADY_OCCUPIED
```

**정렬 삽입**은 동시 트랜잭션 간 InnoDB 락 획득 순서를 고정해 데드락 확률을 낮춘다.

**부분 재시도는 금지다.** 제약 위반을 잡은 시점의 트랜잭션은 rollback-only이므로 예외를 변환해 던지기만 하고 전체 롤백에 맡긴다. 그래야 점유 행이 부분적으로 남지 않는다.

이 선삭제가 정합성을 완결하므로 **만료 정리 배치는 없다.** 배치는 순수 용량 회수용이며, 필요해지면 `DELETE WHERE expires_at <= cutoff`는 멱등이라 다중 replica에서도 분산 락 없이 안전하다.

## 잔여석 계산

행이 구간 단위로 전개되어 있으므로 좌석 수를 셀 때는 **반드시 `COUNT(DISTINCT seat_id)`** 를 써야 한다.

```sql
-- 열차 검색: 여러 스케줄의 CarType별 점유 좌석 수를 한 번에
SELECT train_schedule_id, car_type, COUNT(DISTINCT seat_id)
  FROM seat_occupancy
 WHERE train_schedule_id IN (...) AND expires_at > NOW()
   AND section_order >= :searchDepStopOrder
   AND section_order <  :searchArrStopOrder
 GROUP BY train_schedule_id, car_type;
```

`train_car_id` / `car_type` 을 역정규화해 두어 seat·train_car 조인이 필요 없다.

잔여석 = `총 좌석 - 점유 좌석`. 예약과 확정 예매가 한 집계에 포함되므로 뺄셈이 한 번이다.

## 인덱스

| 인덱스 | 용도 |
|--------|------|
| `uk_seat_occupancy_section (train_schedule_id, seat_id, section_order)` | **좌석 충돌 방어의 유일한 지점** |
| `idx_seat_occupancy_section (train_schedule_id, section_order, expires_at)` | 열차 검색 CarType별 집계 |
| `idx_seat_occupancy_car (train_schedule_id, train_car_id, section_order, expires_at)` | 객차 목록·좌석 상세 |
| `idx_seat_occupancy_reservation (reservation_id)` | 예약 단위 확정·해제 |
| `idx_seat_occupancy_booking (booking_id)` | 예매 단위 해제 |

## Related Classes

- `SeatOccupancy` — 점유 엔티티, `NEVER_EXPIRES` 센티넬 보유
- `SeatOccupancyService` — hold / confirm / release, 제약 위반의 비즈니스 예외 변환
- `SeatOccupancyRepository` — 선삭제·확정·해제 벌크 JPQL
- `SeatOccupancyQueryRepository` — QueryDSL 집계 (`countDistinct`)
- `ReservationFacade` — 예약 생성·삭제 조율 (단일 트랜잭션)
- `PaymentFacade` — 결제 승인 시 확정 전이

## 제약: MySQL 전용 문법 금지

테스트가 H2(MODE=MYSQL)에서 돌기 때문에 `INSERT ... ON DUPLICATE KEY UPDATE`, `INSERT IGNORE`, `SELECT ... FOR UPDATE SKIP LOCKED` 를 쓸 수 없다. 벌크 연산은 표준 JPQL로 작성한다.

`DataIntegrityViolationException`은 Spring이 벤더별 예외를 번역한 것이라 H2/MySQL 모두 동일하게 잡힌다. `SQLIntegrityConstraintViolationException`을 직접 잡으면 H2에서 깨진다.

## 왜 PostgreSQL EXCLUDE가 아닌가

PostgreSQL이라면 `EXCLUDE USING gist (train_schedule_id WITH =, seat_id WITH =, section WITH &&)` 로 구간 전개 없이 같은 불변식을 표현할 수 있다. 다만 이 프로젝트는 MySQL을 쓰고, 구간 전개는 정확성이 동일하면서 조회 인덱스는 오히려 더 잘 탄다. 대가는 행 수가 구간 수만큼 늘어나는 것이며 운행일 기준 아카이빙으로 통제한다.
