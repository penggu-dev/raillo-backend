# Testing Guide

## Test Environment

테스트는 H2 인메모리 DB와 임베디드 Redis(포트 63790)를 사용한다. 통합 테스트는 `@ServiceTest`를 사용한다:

- `@ActiveProfiles("test")` — 테스트 프로파일 활성화
- `DatabaseCleanupExtension` — 매 테스트 후 모든 테이블 TRUNCATE
- `RedisCleanupExtension` — 매 테스트 후 Redis FLUSH
- `RedisServerExtension` — 임베디드 Redis 라이프사이클

> ⚠️ **테스트 메서드에 `@Transactional`을 절대 사용하지 말 것** — 트랜잭션 전파 이슈를 숨긴다. 명시적 cleanup을 사용한다.

## Test Strategy by Layer

| Type | Target | Annotation | Data Setup |
|------|--------|------------|------------|
| Domain Unit Test | Entity | None (POJO) | Fixture |
| Service Integration Test | Service | `@ServiceTest` | Helper |

## Test Writing Conventions

### BDD Comments Required

```java
// given - Test setup
// when - Action under test
// then - Assertions
```

### @DisplayName

상황과 예상 결과를 묘사하는 완전한 한국어 문장으로 작성한다.

### Method Naming

영어, 의도가 분명한 짧은 이름. 예: `cancel_success`, `cancel_fail_if_already_cancelled`

### Example

```java
@Test
@DisplayName("예매 취소 시 상태가 CANCELLED로 변경되고 취소 시간이 설정된다")
void cancel_success() {
    // given
    BookingResult bookingResult = bookingTestHelper.createDefault(member, scheduleResult);

    // when
    bookingService.cancel(bookingResult.booking().getId());

    // then
    Booking cancelled = bookingRepository.findById(bookingResult.booking().getId()).orElseThrow();
    assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
}
```

## Fixture vs Helper

| | Fixture | Helper |
|--|---------|--------|
| Purpose | In-memory POJO (domain tests) | DB-persisted entity (service tests) |
| Location | `support/fixture/` | `support/helper/` |

### Available Fixtures

`MemberFixture`, `BookingFixture`, `SeatBookingFixture`, `TicketFixture`, `OrderFixture`, `PaymentFixture`, 그리고 train 관련 fixture는 `support/fixture/train/`:
`TrainFixture`, `TrainCarFixture`, `SeatFixture`, `StationFixture`, `TrainScheduleFixture`, `ScheduleStopFixture`, `StationFareFixture`

## Member Setup (Fixture Pattern)

Member는 별도 Helper 없이 `MemberFixture` + repository.save 패턴을 사용한다:

```java
// Default member
Member member = memberRepository.save(MemberFixture.create());

// Second member (different email/memberNo)
Member otherMember = memberRepository.save(MemberFixture.createOther());

// Custom member
Member custom = memberRepository.save(
    MemberFixture.builder()
        .withEmail("custom@example.com")
        .withMemberNo("202507300003")
        .build()
);
```

## TrainTestHelper

```java
// Default: 2 cars (1 standard, 1 first), 4 seats (2 + 2)
Train train = trainTestHelper.createKTX();

// Custom row count per car
Train train = trainTestHelper.createCustomKTX(3, 2);  // 3 standard rows, 2 first rows

// Realistic train with multiple cars
// Args: standardCarCount, firstClassCarCount, standardRowsPerCar, firstClassRowsPerCar
Train train = trainTestHelper.createRealisticTrain(3, 2, 12, 8);

// Get available seats for booking
List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.STANDARD, 2);
```

## TrainScheduleTestHelper

```java
// Default: 서울→부산, 05:00→08:00
TrainScheduleResult result = trainScheduleTestHelper.createDefault(train);

// Custom with intermediate stops
trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 25000, 50000);
TrainScheduleResult result = trainScheduleTestHelper.builder()
    .scheduleName("KTX 101")
    .operationDate(LocalDate.of(2025, 1, 15))
    .train(train)
    .addStop("서울", null, LocalTime.of(6, 0))           // departure: null for first
    .addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
    .addStop("부산", LocalTime.of(9, 0), null)           // arrival: null for last
    .build();
```

## BookingTestHelper

```java
// Default: first→last stop, 1 standard seat, adult
BookingResult result = bookingTestHelper.createDefault(member, scheduleResult);

// Custom with specific stops and seats
ScheduleStop daejeonStop = trainScheduleTestHelper.getScheduleStopByStationName(result, "대전");
BookingResult result = bookingTestHelper.builder(member, scheduleResult)
    .setDepartureScheduleStop(daejeonStop)
    .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
    .addSeatsByCarType(CarType.FIRST_CLASS, 1, PassengerType.CHILD)
    .build();
```

## OrderTestHelper

```java
// Default order
OrderResult result = orderTestHelper.createDefault(member, scheduleResult);

// Round trip order (multiple OrderBookings)
OrderResult result = orderTestHelper.builder(member)
    .addOrderBooking(goingSchedule)
        .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
        .and()
    .addOrderBooking(returnSchedule)
        .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
        .and()
    .build();
```

> **Note**: `addSeatsByCarType()`은 이미 예매된 좌석을 자동으로 제외한다.
