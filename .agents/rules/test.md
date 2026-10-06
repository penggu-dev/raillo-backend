# 테스트 규칙

테스트 환경, 금지 사항, 작성 컨벤션, 데이터 생성 수단을 모두 정한다. 테스트에 관한 단일 소스다.

`/test` 스킬은 이 문서를 따라 테스트를 작성하는 워크플로를 담고, 규칙을 재정의하지 않는다.

## 환경

테스트는 Testcontainers로 띄운 **MySQL 8.4.10과 Valkey 9** 컨테이너를 쓴다. 운영(RDS MySQL 8.4.10, Valkey 9)과 같은 버전이며 실행에 **Docker가 필요하다.**

통합 테스트는 `@ServiceTest`를 쓴다. 네 가지가 함께 적용된다.

| 구성 요소 | 역할 |
|---|---|
| `@ActiveProfiles("test")` | 테스트 프로파일 활성화 |
| `TestContainerInitializer` | MySQL과 Redis 컨테이너를 JVM당 한 번 기동하고 접속 정보를 컨텍스트에 주입 |
| `DatabaseCleanupExtension` | 매 테스트 후 모든 테이블 DELETE. JDBC batch로 일괄 실행 |
| `RedisCleanupExtension` | 매 테스트 후 Redis FLUSH |

### 접속 정보

`application-test.yml`에는 datasource와 Redis 접속 정보가 없다. `TestContainerInitializer`가 컨테이너 기동 후 주입한다. `@ServiceTest`나 `@RedisTest`를 쓰면 자동으로 적용된다.

### 라이프사이클

컨테이너는 `./gradlew test` 실행당 한 번만 기동한다. Gradle이 테스트 JVM을 하나만 띄우고(`forkEvery = 0`), `TestContainerInitializer`가 컨테이너를 `static`으로 잡아 클래스 로딩 시점에 1회만 시작하기 때문이다. Spring 컨텍스트가 여러 개 생성돼도 컨테이너는 그 하나를 공유한다.

명시적인 `stop()` 호출은 두지 않는다. Testcontainers가 Ryuk 리소스 리퍼 컨테이너를 함께 띄워 테스트 JVM과의 연결이 끊기면 자동으로 정리한다. `@AfterAll` 수동 정리와 달리 JVM이 비정상 종료돼도 동작한다.

### 로컬 컨테이너 재사용 (선택)

`~/.testcontainers.properties`에 아래를 넣으면 테스트 실행 사이에 컨테이너를 재사용해 기동 시간을 아낀다. 설정하지 않으면 무시되므로 CI는 매번 새 컨테이너로 실행된다.

```properties
testcontainers.reuse.enable=true
```

## 금지

### 테스트 `@Transactional` 금지

메서드든 클래스든 붙이지 않는다. 두 가지가 깨진다.

- cleanup extension을 우회해서 테스트가 남긴 데이터가 다음 테스트로 새 나간다. 통과하다가 **실행 순서가 바뀌면 깨지는** 형태로 드러나 원인을 찾기 어렵다.
- 프로덕션 코드의 트랜잭션 전파 문제를 숨긴다. 테스트가 트랜잭션을 열어주면 서비스가 트랜잭션 없이 호출됐을 때의 동작을 검증하지 못한다.

예외는 하나다. 트랜잭션 동작 자체를 재현하려고 테스트 안에 중첩 `@Service`를 두는 경우다. 그 `@Service`에 붙는 `@Transactional`은 테스트 클래스의 것이 아니므로 해당하지 않는다. `PaymentOutboxWorkerIntegrationTest`의 `FailingTxService`가 그 예다.

**기존 위반 2건.** 아래 둘은 클래스 레벨에 붙어 있다. 새 코드에서 모방하지 않고, 다른 작업의 PR에서 함께 고치지 않는다.

| 파일 | 비고 |
|---|---|
| `booking/application/TicketNumberTest.java` | |
| `booking/application/BookingServiceSearchFilterTest.java` | 파일 안에 제거 예정 TODO가 있다 |

### `@SpringBootTest`의 initializer

`@ServiceTest` 대신 `@SpringBootTest`를 직접 쓰면 컨테이너가 붙지 않는다. 아래를 함께 붙인다.

```java
@ContextConfiguration(initializers = TestContainerInitializer.class)
```

## 테스트가 반드시 있어야 하는 변경

- **기능 추가** — 추가한 동작을 고정하는 테스트가 있다.
- **버그 수정** — **수정 전에 실패하는 테스트**가 있다. 수정 후에만 통과해야 버그가 실제로 고쳐졌음을 증명한다.
- **리팩터링** — 새 테스트가 아니라 기존 테스트가 동작을 고정하고 있는지 확인한다. 고정하는 테스트가 없으면 리팩터링 전에 먼저 추가한다.

설정, 인프라, 문서 변경은 테스트가 없을 수 있다. 그때는 없는 이유를 적는다. 이 판정을 이슈 체크리스트와 PR에서 쓴다.

## 테스트 유형

| 유형 | 대상 | 애노테이션 | 데이터 생성 | 테스트가 놓이는 곳 |
|---|---|---|---|---|
| 도메인 단위 테스트 | `raillo-domain`의 `domain/` | 없음 | Entity 정적 팩토리 | `raillo-domain/src/test` |
| 서비스 통합 테스트 | `raillo-api`의 Service, Facade, Validator, Calculator | `@ServiceTest` | Fixture와 Helper | `raillo-api/src/test` |

`raillo-api`의 기본은 `@ServiceTest`다. 판단 기준은 패키지가 아니라 **테스트가 컨테이너를 필요로 하는지**다. 대상이 의존성을 주입받지 않아도, 검증에 쓸 데이터를 Helper로 DB에 만들어야 하면 컨테이너가 필요하다.

`SeatAvailabilityCalculatorTest`가 그 예다. `SeatAvailabilityCalculator`는 주입받는 의존성이 없지만, 좌석과 점유 상태를 Helper로 만들어 넣어야 하므로 `@ServiceTest`를 쓴다. 반대로 `FareCalculator`는 `StationFareRepository`를 주입받으므로 당연히 `@ServiceTest`다.

`raillo-domain` 테스트는 Fixture를 쓰지 않는다. API 모듈의 테스트 코드에 접근할 수 없기 때문이다. DB도 쓰지 않는다. 반복되는 생성 코드는 테스트 클래스의 private 메서드로 둔다.

## 작성 컨벤션

- 파일명은 `{ClassName}Test.java`다.
- 모든 테스트에 `// given`, `// when`, `// then` 주석을 넣는다.
- `@DisplayName`은 상황과 예상 결과를 묘사하는 **완전한 한국어 문장**으로 쓴다.
- 메서드명은 영어로, 의도가 분명한 짧은 이름을 쓴다. `cancel_success`, `cancel_fail_if_already_cancelled`
- 서비스 통합 테스트의 공통 데이터는 `@BeforeEach`에서 준비한다.
- BigDecimal 비교는 `isEqualByComparingTo`를 쓴다.
- 예외 검증은 예외 타입과 함께 메시지나 에러 코드를 확인한다.
- 기존 테스트가 더 강한 로컬 컨벤션을 보여주면 그 패턴을 따른다.

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

## Fixture와 Helper

| | Fixture | Helper |
|---|---|---|
| 역할 | 메모리 POJO | DB에 저장된 엔티티 |
| 위치 | `raillo-api/src/test/.../support/fixture/` | `raillo-api/src/test/.../support/helper/` |

Helper가 있으면 Helper를 우선한다.

### Fixture 목록

`MemberFixture`, `BookingFixture`, `SeatBookingFixture`, `TicketFixture`, `OrderFixture`, `PaymentFixture`, `ReservationFixture`

`support/fixture/train/` 아래 — `TrainFixture`, `TrainCarFixture`, `SeatFixture`, `StationFixture`, `TrainScheduleFixture`, `ScheduleStopFixture`, `StationFareFixture`

### 서비스 통합 테스트 공통 셋업

```java
member = memberRepository.save(MemberFixture.create());
train = trainTestHelper.createKTX();
scheduleResult = trainScheduleTestHelper.createDefault(train);
```

필요한 예매나 주문은 `bookingTestHelper`, `orderTestHelper`로 만든다.

### Member

별도 Helper 없이 `MemberFixture`와 `repository.save`를 쓴다.

```java
Member member = memberRepository.save(MemberFixture.create());
Member otherMember = memberRepository.save(MemberFixture.createOther());   // 다른 email과 memberNo

Member custom = memberRepository.save(
    MemberFixture.builder()
        .withEmail("custom@example.com")
        .withMemberNo("202507300003")
        .build()
);
```

### TrainTestHelper

```java
// 기본: 객차 2개(일반 1, 특실 1), 좌석 4개(2 + 2)
Train train = trainTestHelper.createKTX();

// 객차별 열 수 지정
Train train = trainTestHelper.createCustomKTX(3, 2);   // 일반 3열, 특실 2열

// 여러 객차를 가진 현실적인 열차
// 인자: 일반 객차 수, 특실 객차 수, 일반 객차당 열 수, 특실 객차당 열 수
Train train = trainTestHelper.createRealisticTrain(3, 2, 12, 8);

// 예매 가능한 좌석 조회
List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.STANDARD, 2);
```

### TrainScheduleTestHelper

```java
// 기본: 서울에서 부산, 05:00 출발 08:00 도착
TrainScheduleResult result = trainScheduleTestHelper.createDefault(train);

// 중간 정차역을 둔 경우
trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 25000, 50000);
TrainScheduleResult result = trainScheduleTestHelper.builder()
    .scheduleName("KTX 101")
    .operationDate(LocalDate.of(2025, 1, 15))
    .train(train)
    .addStop("서울", null, LocalTime.of(6, 0))             // 첫 역은 도착 시각이 null
    .addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
    .addStop("부산", LocalTime.of(9, 0), null)             // 마지막 역은 출발 시각이 null
    .build();
```

### BookingTestHelper

```java
// 기본: 첫 역에서 마지막 역, 일반석 1석, 성인
BookingResult result = bookingTestHelper.createDefault(member, scheduleResult);

// 정차역과 좌석을 지정한 경우
ScheduleStop daejeonStop = trainScheduleTestHelper.getScheduleStopByStationName(result, "대전");
BookingResult result = bookingTestHelper.builder(member, scheduleResult)
    .setDepartureScheduleStop(daejeonStop)
    .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
    .addSeatsByCarType(CarType.FIRST_CLASS, 1, PassengerType.CHILD)
    .build();
```

### OrderTestHelper

```java
OrderResult result = orderTestHelper.createDefault(member, scheduleResult);

// 왕복 주문 (OrderBooking 여러 개)
OrderResult result = orderTestHelper.builder(member)
    .addOrderBooking(goingSchedule)
        .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
        .and()
    .addOrderBooking(returnSchedule)
        .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
        .and()
    .build();
```

`addSeatsByCarType()`은 이미 예매된 좌석을 자동으로 제외한다.

### Redis Helper (예약 생성 경로)

예약 생성은 DB 대신 Redis 기준정보 캐시를 읽는다. `RedisCleanupExtension`이 테스트마다 Redis를 비우므로 **`@BeforeEach`에서 다시 적재한다.** 적재하지 않으면 검증 단계에서 실패한다.

```java
// DB에 만든 열차와 스케줄을 Batch와 같은 형식으로 Redis에 적재 (좌석, 객차, 운행, 정차역, 운임)
trainCacheTestHelper.seed(train, scheduleResult);

// 좌석 점유를 직접 기록 (다른 예약의 점유, 예매 점유)
seatOccupancyTestHelper.markReserved(scheduleId, trainCarId, seatId, 0, 2, "OTHER");
seatOccupancyTestHelper.markBooked(scheduleId, trainCarId, seatId, 0, 2, "77");
seatOccupancyTestHelper.valueOf(scheduleId, trainCarId, seatId, 1);   // "R:RV..." / "B:77" / null

// 예약을 점유 없이 Redis에 저장 (본문과 회원 인덱스)
Reservation reservation = reservationTestHelper.save(
    ReservationFixture.builder()
        .withMemberNo(memberNo)
        .withTrainSchedule(scheduleResult.trainSchedule())
        .withDepartureStop(scheduleResult.scheduleStops().get(0))
        .withArrivalStop(scheduleResult.scheduleStops().get(1))
        .withSeats(List.of(ReservationFixture.seat(seatId, PassengerType.ADULT)))
        .build());
```

## 실행

실행 명령은 루트 `AGENTS.md`의 Build & Test 절에 있다.

테스트를 쓰거나 고쳤으면 **실행해서 통과를 확인한다.** 통과를 확인하지 않은 테스트는 작업이 끝난 것이 아니다.
