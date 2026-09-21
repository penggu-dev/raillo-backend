# Reservation 기반 결제·좌석 이관 검토 및 작업 계획

작성일: 2026-09-20. 코드·브랜치·GitHub 이슈를 확인한 설계 제안이며 구현 완료 보고가 아니다.

## 1. 요청과 작업 경계

- #273의 DB 조회 없는 예약 생성 구조를 기준으로 현재 #266 Outbox 브랜치를 이관한다.
- 사용자 확정 범위: #273 / PR #279는 예약 생성까지, #266은 기존 폴링 Worker 재사용, PendingBooking 레거시의 Reservation 이관 및 결제 중 좌석 보호까지 담당한다.
- `R:` → `B:` 상세 구현은 후속 PR이다. 이번에는 확정 처리 클래스/인터페이스와 흐름 주석만 마련한다. 예약 본문/회원 인덱스의 확정 후 정리도 실제 확정 처리와 함께 후속 PR로 넘긴다.
- 사용자는 결제 확정 DB 트랜잭션 이후 Redis 후처리를 기다리지 않는다.
- 예약 생성은 Redis만 사용한다. Payment, Order, Booking, Ticket 및 Outbox의 영속 저장은 MySQL에 유지한다. 결제까지 DB를 없앤다는 뜻으로 확장하지 않는다.
- 이번 PR은 기존 Outbox 폴링을 유지한다. Kafka 도입은 별도 후속 PR에서 판단·구현한다.
- 이번 조사에서는 rebase, 제품 코드 수정, 테스트 실행, 커밋, 원격 변경을 하지 않았다.

## 2. 확인한 브랜치와 근거

| 항목 | 확인 결과 |
|---|---|
| 현재 브랜치 | `feature/266-payment-outbox-worker`, HEAD `89b6d083` |
| 기준 원격 브랜치 | `origin/feature/273-reservation-redis`, HEAD `a66c8242` |
| 공통 조상 | `f83dda0c78ebb645f390484e0a05b2a3289bd4f8` |
| #266 고유 커밋 | 16개 |
| 로컬 #273 | `07fab609`, 원격과 이력 기준 20 ahead / 22 behind |
| 로컬/원격 #273 실제 파일 차이 | 7개. 원격 최신에는 한 예약의 좌석이 한 객차에 속해야 한다는 수정 포함 |
| Git 통합 모의 확인 | `git merge-tree --write-tree --name-only --messages HEAD origin/feature/273-reservation-redis` |
| 파일 충돌 | `PendingBookingFacade.java` modify/delete 1건. rebase의 커밋별 충돌 수와 같다는 뜻은 아님 |
| 기존 #266 PR | 해당 head의 PR 없음 |
| 테스트 엔진 | #273 기준 MySQL `8.4.10`, `valkey/valkey:9.0-alpine` |

실행 시 원격 HEAD를 다시 확인한다. 로컬 #273을 기준으로 삼거나 두 #273 이력을 모두 합치지 않는다. 기존 #266과 로컬 #273을 보존하고 원격 #273 위로 #266의 변경을 올린다. 삭제된 `PendingBookingFacade`는 되살리지 않는다.

참고:
- [#279 Reservation PR](https://github.com/penggu-dev/raillo-backend/pull/279)
- [#266 Outbox 이관](https://github.com/penggu-dev/raillo-backend/issues/266)
- [#274 오래된 이벤트와 새 소유자 보호](https://github.com/penggu-dev/raillo-backend/issues/274)
- [#275 잔여석 중복 차감](https://github.com/penggu-dev/raillo-backend/issues/275)
- [#272 통합 검증](https://github.com/penggu-dev/raillo-backend/issues/272)

PR 본문 일부는 최신 커밋보다 오래됐다. 예를 들어 여러 객차 예약을 허용한다는 본문과 달리 `a66c8242`는 한 객차만 허용한다. #275 본문에 있는 “#273이 검색까지 이관”한다는 전제도 실제 코드와 다르다. 코드와 테스트 계약을 우선한다.

## 3. 실제 Redis 계약

| 데이터 | 위치 | 만료 |
|---|---|---|
| 예약 본문 | String `{schedule:1001}:reservation:RV...` | 예약 TTL `EX` |
| 좌석·구간 점유 | Hash `{schedule:1001}:car:231:seats`, field `46456:0` | `R:RV...` field는 `HEXPIRE` |
| 확정 예매 점유 | 같은 Hash/field, 값 `B:77` | field 만료 없음, Hash 키 만료는 유지 |
| 회원 예약 인덱스 | Hash `member:{memberNo}:reservations`, field 예약 ID → 운행 ID | field TTL |

따라서 예약 JSON 키의 `PERSIST`만으로 좌석이 보호되거나 `B:`로 바뀌지는 않는다. `R:`/`B:`는 좌석 Hash field의 값이다. `B:`의 “영구”는 **예약 TTL로 사라지지 않는다는 의미**다. Hash 키는 `TrainCacheKey.expireAtEpochSecond(operationDate)`에 따라 운행일 + 2일의 한국 시간 자정에 만료한다.

확정 예매의 영속 원본은 MySQL의 Booking/SeatBooking/Ticket이다. 이번 PR에서는 보호된 R field를 유지한다. 후속 PR에서 B로 바꾼 뒤 예약 JSON과 회원 인덱스를 정리한다. 예약 본문은 원래 TTL로 사라질 수 있으므로 보호/후처리 대상은 영속 snapshot에서 식별한다.

`Reservation`은 객차 ID, 정차 순서, 좌석별 승객 유형·운임, 운행일 등을 이미 가진다. 후처리가 이 값을 얻으려고 TrainSeatReader/TrainScheduleReader로 재조회할 필요가 없다.

## 4. Kafka 대신 폴링을 사용한 이유와 선택지

확인 가능한 과거 결정은 `docs/payment-consistency.md`의 “MSA 분리 시점의 브로커 재검토”다. 서비스 간 이벤트가 필요할 때 `Outbox → Kafka Publisher`로 확장한다고 명시되어 있다. 현재 단일 애플리케이션의 Redis 후처리와 기존 MySQL만으로 재시도·다중 소비 제어가 가능하다는 점이 폴링 선택의 근거로 해석된다. 당시 Kafka 대비 부하 측정으로 결정했다는 근거는 찾지 못했다.

현재 구현은 이미 존재한다:
- `PaymentOutboxWorker.poll()`의 `@Scheduled`.
- `FOR UPDATE SKIP LOCKED` 잠금 조회.
- DONE / 재시도 / FAILED 전이와 지수 backoff.
- dev/prod 폴링 간격 5초, 배치 크기 50, 초기 backoff 30초, 최대 backoff 10분, 최대 시도 설정 5.

| 선택지 | 경로 | 장점 | 추가 책임 |
|---|---|---|---|
| A. 기존 폴링 유지 — 이번 PR 확정 | DB Outbox → Worker → Valkey | 기존 구현 재사용, 전달 인프라 추가 없음 | DB 조회·잠금, 지연·적체 관측 |
| B. Outbox relay + Kafka | DB Outbox → 폴링 Publisher → Kafka → Consumer → Valkey | 소비자 분리, 구독 확장, 재생 | DB 폴링은 남음. 발행 성공과 처리 완료 상태 분리, 브로커·consumer 운영 |
| C. CDC + Kafka | DB Outbox → Debezium → Kafka → Consumer → Valkey | 애플리케이션의 outbox 테이블 폴링 제거 | binlog/CDC offset·보존·권한·connector·복구 운영 |

Kafka를 사용해도 DB 확정과 이벤트 발행을 안전하게 연결하는 Outbox는 유지한다. DB commit 후 `KafkaTemplate.send()`만 호출하면 두 동작 사이의 프로세스 종료를 복구할 영속 기록이 없다. Kafka transaction도 MySQL/Valkey 변경까지 자동으로 하나의 transaction에 넣지 않는다.

Kafka consumer의 Redis 쓰기 성공 후 offset commit 실패는 중복 처리로 이어질 수 있다. 따라서 아래 소유권 비교와 멱등 처리는 세 안 모두 필요하다. Kafka의 낮은 전달 지연을 TTL 보호의 근거로 사용하지 않는다.

Kafka 도입 시 추가 계약:
- Outbox의 “발행 완료”와 Redis의 “적용 완료”를 구분한다. 현재 DONE의 의미를 그대로 발행 성공에 재사용하지 않는다.
- 메시지에 eventId/schemaVersion/bookingId/reservationId를 유지한다.
- 승인·취소가 같은 bookingId의 순서를 공유하게 설계한다. 재시도 토픽으로 이동하면 순서가 바뀔 수 있어 revision 또는 DB 최신 상태 검증이 필요하다.
- 소비 성공 후 offset 반영, 재시도/DLT, backlog·consumer lag 관측을 함께 구현한다.
- CDC 선택 시 현행 상태 UPDATE 중심 outbox와 Event Router의 INSERT 이벤트 계약을 조정한다.

공식 근거: [Kafka 외부 시스템 처리 보장](https://kafka.apache.org/41/design/design/), [Debezium Outbox Event Router](https://debezium.io/documentation/reference/transformations/outbox-event-router.html).

### 현재 Kafka 이관 판단

Kafka 도입을 검토할 이유는 있다. 좌석 반영을 독립 소비자로 운영하거나, 결제 이벤트를 알림·정산 등 여러 소비자가 받거나, 재생/적체 흡수와 소비자별 확장이 필요하면 모놀리스에서도 합리적이다. MSA 전환은 필수 조건이 아니다.

현재 확인한 요구는 단일 Redis 후처리이고, 폴링의 처리량/지연이 요구를 못 맞춘다는 측정 근거는 없다. 따라서 이번 이관의 선행 조건으로 Kafka를 넣을 필요는 없다. 후속 PR의 목적을 이벤트 전달 기반 확립이나 운영 경험 확보로 명시한다면 그 자체도 합리적인 프로젝트 목표다. 이를 현행 성능 문제 해결의 증거와 혼동하지 않는다.

이전의 유보 이유는 Kafka의 부적합성이 아니라 당시 필요한 비동기 처리·재시도를 기존 DB Worker로 만족시킬 수 있는 반면, Kafka 운영과 외부 저장소 멱등 처리를 추가해야 한다는 비용 대비 효과였다. R→B가 단순 정리보다 중요한 작업이 됐다는 사실은 내구성과 소유권 보호의 필요를 높이지만, 그 자체로 Kafka가 필수라는 근거는 아니다.

## 5. 비동기 확정의 선행 조건: TTL 공백 제거

현재 구조를 단순 연결하면 다음 순서가 가능하다.

1. A의 예약 TTL 만료 직전에 Toss 승인 시작.
2. Toss 성공 및 DB Booking/Outbox commit.
3. Worker가 처리하기 전에 A의 `R:` field가 만료.
4. DB를 읽지 않는 새 예약 Lua가 빈 field에 다른 예약 C를 생성.
5. A의 이벤트가 뒤늦게 도착. C를 덮으면 소유권 훼손이고, 덮지 않으면 DB 예매와 Redis 점유가 불일치.

Outbox payload를 보강하거나 Kafka를 도입하는 것만으로 이 순서를 막을 수 없다. 결제 준비 시 SQL 조회만 해두는 것도 이후의 만료·경합을 막지 못한다.

### 이번 PR: 결제 시작 시 점유 보호 / 후속 PR: 확정 반영

사용자가 요청한 비동기 확정을 유지하는 안이다. 현재 #273에는 없는 **새 계약**이다.

1. 기존 승인 결과 재사용 검증을 먼저 수행한다. 이미 SUCCEEDED이면 사라진 예약을 읽지 않고 기존 결과를 반환한다.
2. TX A에서 attempt와 복구 가능한 예약 스냅샷/주문 연결을 영속화한다.
3. Toss 호출 전에 운행별 Lua로 모든 대상 field가 자기 `R:{reservationId}`인지, 예약이 유효한지 검사한다. 하나라도 다르면 그 운행에는 아무 변경도 하지 않는다.
4. 같은 slot의 `{schedule:id}:reservation-payment:{reservationId}` marker에 `paymentId/attemptId`를 기록하고, 해당 R field의 TTL을 제거한다. 다른 attempt가 같은 예약을 결제하는 것을 거절한다. 키 만료는 운행 보존 기한을 따른다. field TTL 제거는 Valkey `HPERSIST`를 사용한다.
5. 여러 운행이 포함되면 운행별 보호를 모두 완료한 뒤에만 Toss를 호출한다. 한 운행이라도 실패하면 Toss를 호출하지 않고 획득한 보호를 소유권 비교로 해제한다. Cluster 전체 원자성을 가정하지 않는다.
6. Toss 성공 후 TX B에서 Order/Booking/SeatBooking/Ticket/Payment/Attempt/Outbox를 함께 commit하고 응답한다.
7. **후속 PR:** Worker가 자기 marker와 R/B 소유자를 검사하여 `R:` → `B:`를 반영한다. 회원 인덱스 정리는 별도 slot이므로 이후 멱등 처리한다. 이번에는 이 순서를 처리 클래스 주석으로 남긴다.

예약 본문과 회원 인덱스는 원래 TTL로 만료될 수 있다. 점유 보호 이후의 처리와 복구는 이 두 키에 의존하지 않고 DB에 남긴 스냅샷을 사용한다. 원래 expiresAt을 지난 예약은 새로운 결제 시도에 재사용하지 않는다.

이 보호는 실패 시 정리 책임을 만든다:
- Toss 확정 실패 또는 Toss를 호출하지 않은 보호 실패: DB 실패 상태 및 보호 해제 작업을 영속 기록하고, 자기 marker와 R field만 해제한다. 해제 응답 유실도 재시도한다.
- Toss 결과 불명 또는 Toss 성공/DB 실패: 보호를 임의로 만료시키거나 풀지 않는다. #270 대사로 성공 확정/확정 실패를 구분한다.
- 프로세스 종료로 보호 일부만 성공: TX A의 영속 기록에서 대사·정리한다.
- Valkey 키 자체의 유실/축출은 field TTL 제거로 해결되지 않는다. DB 확정 예매와 진행 중 결제 보호 복구, 복구 중 신규 판매 차단 정책이 필요하다.

**범위 영향:** 결제 중 보호를 도입하면 #270의 복구 계약과 실패 보호 해제의 재시도 경로가 운영 선행 조건이 된다. #266을 개발상 별도 PR로 나눌 수는 있지만 보호만 걸고 복구 수단이 없는 상태를 운영 완료로 보지 않는다. 실패 보호 해제는 취소 예매 이벤트 `BOOKING_CANCELLED`와 구분한다.

대안은 결제 응답 전에 최소 `R:` → `B:` 전환을 수행하고 인덱스/본문 정리만 비동기화하는 것이다. 그래도 Toss 호출 중 만료 및 Redis 성공/DB 실패의 보상은 필요하다. 사용자가 확정 전환 자체를 비동기로 요청했으므로 기본 계획에는 채택하지 않았다.

공식 근거: [Valkey HPERSIST](https://valkey.io/commands/hpersist/). HPERSIST는 field 만료를 제거하며 Hash 키의 만료를 제거하는 명령이 아니다.

## 6. Payload 계약 및 후속 PR 확정 처리 규칙

이번에는 Reservation 기반 payload/처리 클래스의 계약까지만 마련한다. 아래 R→B Lua, 멱등 확정, 확정 후 정리는 후속 PR의 구현 조건이며 이번 PR의 완료 조건이 아니다.

**미구현 처리기 실행 정책:** 빈 메서드를 정상 반환시켜 Worker가 DONE으로 기록하면 안 된다. 처리 클래스는 미등록 상태로 두고 실행되면 미구현임을 명시적으로 실패시킨다. 현재 실제 소비 대상인 BOOKING_CONFIRMED 구현이 없으므로 이번 PR의 기본 자동 스케줄은 disabled로 두어 이벤트를 PENDING에 보존한다. Worker 자체의 DONE/재시도/FAILED 검증은 테스트 전용 처리기로 수행한다. 후속 PR에서 실제 처리기 등록·통합 검증 후 scheduler를 활성화한다. 이벤트를 계속 실패시켜 FAILED를 소진하는 방식으로 대기시키지 않는다.

현재 `BookingConfirmedPayload`에는 pendingBookingId, memberNo, scheduleId, stopId, seatIds만 있다. 생성된 Booking ID가 없고, `BookingCreator.createBookingFromOrder()`의 반환도 void다. 이대로는 `B:{bookingId}`를 기록할 수 없다.

새 payload는 `schemaVersion=2`로 구분하며 최소 다음 스냅샷을 갖는다.

```json
{
  "schemaVersion": 2,
  "paymentId": 501,
  "attemptId": "example-attempt",
  "bookings": [{
    "reservationId": "RV20260918143000A1B2C3",
    "bookingId": 77,
    "memberNo": "202601010001",
    "trainScheduleId": 1001,
    "operationDate": "2026-10-20",
    "departureStopOrder": 0,
    "arrivalStopOrder": 3,
    "seats": [{"seatId": 46456, "trainCarId": 231}]
  }]
}
```

- Booking 생성 결과에서 reservationId와 bookingId를 명시적으로 연결한다. 리스트 순서나 구간 값만으로 추정하지 않는다.
- 만료된 Reservation, 회원 인덱스, 열차 기준정보 캐시를 다시 읽지 않고 대상 field와 소유자를 결정한다.
- Lua는 한 운행의 모든 field와 marker를 먼저 검사한 다음 변경한다. 인자/키 타입 오류도 쓰기 전에 검증한다. Lua의 원자 실행을 오류 발생 시 자동 롤백으로 해석하지 않는다.
- 자기 R는 같은 예매의 B로 바꾸고 field TTL을 제거한다. 같은 B는 중복 성공이며 field TTL도 만료 없음인지 보장한다.
- 다른 R, 다른 B, 알 수 없는 값은 덮거나 지우지 않는다. 충돌/오염으로 실패시키고 기록한다.
- 정상 경로에서 사라진 field는 무조건 B로 복원하지 않는다. 취소 후 오래된 승인 이벤트가 좌석을 부활시킬 수 있기 때문이다. DB 최신 예매 상태·복구 권한을 확인하는 별도 복구 경로로 보낸다.
- 이미 운행 보존 기한이 지난 이벤트는 DB 최신 상태와 정책을 확인하고 불필요한 키를 재생성하지 않는다. 명시적 처리 결과와 로그를 남긴다.
- 여러 운행 중 일부 성공 후 실패하면 다음 실행에서 같은 B를 인정하여 나머지를 처리한다.
- R→B 성공 전에 예약 본문/marker를 삭제하지 않는다. 별도 slot의 회원 인덱스 정리 실패는 Outbox 재시도로 처리한다.
- payload 누락/지원하지 않는 버전/빈 목록은 발행 계약 오류다. 조용히 DONE으로 처리하지 않는다.
- 취소 및 재판매 이후 승인 이벤트 재실행은 #259의 최신 상태/순서 보장과 함께 검증한다. #266만으로 취소까지 해결했다고 주장하지 않는다.

## 7. 파일별 이관 지도

경로 접두사: API=`raillo-api/src/main/java/com/sudo/raillo`, Domain=`raillo-domain/src/main/java/com/sudo/raillo`.

| 대상 | 변경 |
|---|---|
| API `payment/adapter/webapi/dto/PaymentPrepareRequest`, `application/command/PaymentPrepareCommand` | `reservationIds` 계약, 빈 값·중복 ID 검증, Swagger 갱신 |
| API `payment/application/{PaymentPrepareService,PaymentApprovalStarter,PaymentApprovalStart,PaymentApprovalFinalizer,PaymentConfirmService}` | Reservation 입력과 스냅샷, 승인 보호 및 새 Outbox 흐름 |
| API `payment/application/required/PendingBookingReader`, `adapter/integration/PendingBookingAdapter` | ReservationReader와 새 예약 조회 adapter로 교체 |
| API `payment/application/required/{OrderReader,OrderRegister}`, 대응 adapter | Reservation 타입/ID로 변경 |
| API `order/application/OrderService`, `dto/OrderBookingInfo`, `validator/OrderValidator` | Reservation의 좌석별 fare/totalFare를 주문 스냅샷으로 사용. 중복 DB 운임 재계산 제거 |
| Domain `order/domain/OrderBooking` | Java reservationId 명칭과 복구용 예약 snapshot 저장. 기존 물리 column 변경은 명시적 이행 없이는 하지 않음 |
| API `payment/application/required/BookingCreator`, adapter, `booking/application/service/BookingService` | reservationId→bookingId 매핑 결과 반환 |
| API `payment/application/BookingConfirmedPayload` | version 및 예매 ID·객차·구간 스냅샷 |
| API `payment/application/outbox/BookingConfirmedProcessor` | 구형 삭제/해제 제거, Reservation 기반 클래스와 후속 R→B 흐름 주석. 실제 확정 처리는 미등록 상태 |
| API `booking/infrastructure/{SeatOccupancyRepository,ReservationRedisRepository}` 및 새 보호 저장소 | 이번에는 비교 기반 보호/실패 해제. 확정 Lua와 확정 후 정리는 후속 |
| API `payment/application/outbox/PaymentOutboxWorker` 및 새 scheduler | 스케줄 트리거와 수동 실행 분리, 자동 실행 enable 설정 |
| API `payment/adapter/observability/PaymentMetrics` | cleanup 명칭을 확정 반영 의미에 맞춤. backlog age·처리 지연·충돌 관측 |
| API `booking/application/validator/BookingValidator`와 결제 SeatConflictValidator port/adapter | Reservation의 stopOrder를 사용. 결제 단계 DB 중첩 방어 유지 |
| API `train/application/{facade/TrainSearchFacade,calculator/SeatAvailabilityCalculator}` 및 조회 저장소 | #275에서 새 R/B 조회와 좌석 ID 중복 제거 |
| fixture/helper/결제·주문·검색 테스트 | 새 Reservation 생성, 실제 Valkey 9 Lua 및 트랜잭션 검증 |

예약 생성 Facade는 이미 Redis-only이므로 DB transaction을 새로 추가하지 않는다. Payment의 기존 application component 구성을 전면 재설계하는 작업은 범위에 포함하지 않는다. 새 협력은 required port/adapter를 유지하고 Service→Service 의존을 추가하지 않는다.

## 8. 구형 코드 제거와 검색 범위

#273은 새 예약 생성 API를 추가했으나 결제·검색을 완전히 이관하지 않았다. `TrainSearchFacade`가 `SeatHoldService`를 사용하므로 결제 이관 직후 SeatHold 스택을 일괄 삭제하면 검색을 깨뜨린다.

순서:
1. #266: 결제의 PendingBooking/SeatHold 소비를 새 Reservation 계약으로 교체.
2. #275: 검색·객차 목록·좌석 상세를 새 점유 Hash 기반으로 변경.
3. 실제 참조가 0이 된 구형 domain/port/adapter/service/repository/Lua/config/test만 삭제.

검색의 과도기 계산은 `전체 좌석 - |DB 확정 좌석 ID ∪ Redis R/B 점유 좌석 ID|`로 중복 제거한다. 같은 좌석의 여러 구간도 1석이다. Redis 확정 점유 복구·전환이 완성되면 통합 Hash만 읽는 모델로 단순화할 수 있다. 그 전에 DB와 Redis의 숫자를 각각 빼거나 DB를 제거하지 않는다.

회원 예약 조회/취소 API, 예매 취소 #259, 대사 #270, Batch 예매 좌석 복구는 각각 연결된 후속 작업으로 관리한다. 특히 #275와 확정 좌석 복구는 Redis 예약 경로를 실제 판매에 사용하는 배포의 선행 조건이다.

## 9. 데이터 이행과 배포

- 구형 Outbox payload가 운영에 존재하는지, 이 브랜치가 배포됐는지는 이번 코드 조사로 알 수 없다. 배포 이력/DB의 버전별 PENDING·FAILED 수를 실행 전에 확인한다.
- 기존 payload에는 bookingId가 없다. 구형 이벤트를 새 이벤트처럼 읽어 새 점유를 지우지 않는다. 실제 잔존 데이터가 있으면 DB 예매 매핑을 검증한 변환/드레인 절차를 별도 적용한다.
- `OrderBooking`의 Java 이름 변경과 DB column rename을 분리한다. 현재 prod도 `ddl-auto:update`이므로 JPA 이름 변경만으로 기존 column이 안전하게 rename된다고 가정하지 않는다.
- API 요청 필드 `pendingBookingIds`→`reservationIds`는 클라이언트 변경점이다. 구 필드 alias가 필요한 배포인지 확인하되 구 예약 저장소 fallback을 무조건 유지하지 않는다.
- 최초 활성화 전에 이미 확정된 예매의 B field를 채우거나 예약 생성을 차단한 상태에서 이행한다. 새 결제 이벤트만 처리하면 기존 DB 예매 좌석은 여전히 비어 있다.
- Valkey 9의 실제 명령 지원, persistence/축출 정책 및 장애 후 판매 재개 절차를 검증한다. 키 TTL이 남아 있는 점유 Hash는 volatile 계열 축출 정책에서도 보호되지 않는다.
- #273 머지 전에는 해당 브랜치 base의 의존 PR로 관리하고, develop으로 옮길 때 diff와 테스트를 재확인한다.

## 10. 이번 PR 실행 순서와 완료 조건

1. 브랜치 백업 → 원격 #273 기준 rebase → 삭제 충돌 해소 → 컴파일 기준 확인.
2. 결제 요청/조회/주문을 Reservation으로 이관하고 스냅샷/예매 ID 연결 보강.
3. 결제 중 점유 보호 및 확정 실패 해제의 영속 재시도 계약 구현. #270 복구 인터페이스 연결.
4. Reservation payload 및 확정 처리 클래스/계약/주석 작성. R→B 상세 구현과 실제 처리기 등록은 하지 않는다.
5. Worker 트리거 분리, 예외 전파, backoff, 실패 관측과 운영 재처리 정비.
6. Worker 자체와 Reservation 이관·좌석 보호·미구현 이벤트 PENDING 보존 테스트. R→B 재처리 시나리오는 후속 PR로 분리.
7. 검색 등 남은 구형 의존을 추적해 Reservation 기준으로 이관하고 참조 없는 레거시 제거. 기존 B 복구/배포 전환은 후속 연계 조건 명시.
8. 문서/PR diff와 전체 모듈 회귀 확인.

필수 검증:
- 예약 생성은 새 Redis 기준정보와 Lua만 사용한다.
- 만료 직전 승인, Worker 장기 지연에서도 결제 중/확정 좌석의 재예약이 불가능하다.
- 다른 attempt의 같은 예약 중복 결제를 거절한다.
- 보호된 R을 점유 좌석으로 읽으며 DB 예매와 같은 좌석을 이중 차감하지 않는다.
- 보호 Lua 응답 유실·중복 실행·실패 해제가 다른 소유자의 점유를 변경하지 않는다.
- 미구현 BOOKING_CONFIRMED가 no-op DONE 또는 자동 재시도로 FAILED가 되지 않고 PENDING으로 보존된다.
- field TTL은 제거되고 운행 Hash 키의 절대 만료는 유지된다.
- 자동 폴링을 비활성화한 통합 테스트에서 수동 poll과 경합하지 않는다.
- payload 오류 한 건이 정상 행 진행을 방해하지 않으며 실패 이벤트가 조용히 사라지지 않는다.
- 승인 재요청은 예약 본문/회원 인덱스가 사라진 뒤에도 기존 결과를 반환하고 Outbox를 중복 발행하지 않는다.

후속 PR 완료 조건: 실제 R→B 처리, Redis 성공/DONE commit 실패 재실행, 운행별 부분 성공 복구, 취소 후 오래된 승인 이벤트 방어, 실제 처리기 활성화. Kafka 전달 계층 이관은 이와 별도 변경으로 다룰 수 있다.

상세 작업 체크리스트: `docs/superpowers/plans/2026-09-20-reservation-payment-refactor.md` (로컬 계획 문서, `.gitignore` 대상).
