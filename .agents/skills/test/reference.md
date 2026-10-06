# Test Utilities Reference

`raillo-api/src/test/java/com/sudo/raillo/support/` 아래 유틸 사용법. 시그니처는 실제 소스 기준이다. 여기 없는 메서드는 소스를 직접 확인한다.

## 1. 어노테이션·인프라

| 이름 | 위치 | 내용 |
|------|------|------|
| `@ServiceTest` | `annotation/` | `@SpringBootTest(RANDOM_PORT)` + `test` 프로필 + `TestContainerInitializer` + `DatabaseCleanupExtension` + `RedisCleanupExtension` |
| `@RedisTest` | `annotation/` | `@SpringBootTest(NONE)` + `test` 프로필 + `TestContainerInitializer` + `RedisCleanupExtension` (DB cleanup 없음) |
| `TestContainerInitializer` | `container/` | MySQL 8.4.10 / Valkey 9 컨테이너를 JVM당 1회 기동해 접속 정보 주입 |
| `DatabaseCleanupExtension` | `extension/` | 매 테스트 **후** 모든 테이블 DELETE |
| `RedisCleanupExtension` | `extension/` | 매 테스트 **후** Redis FLUSH → 캐시는 `@BeforeEach`에서 다시 적재 |
| `TestSecurityConfig` | `config/` | 테스트용 Security 설정 |
| `TestSeatRepository`, `TrainRepository` | `repository/` | 테스트 전용 조회 Repository |

## 2. Fixture (영속화하지 않는 POJO)

정적 `create(...)` = 기본값 객체, `builder()` + `withXxx(...)` + `build()` = 커스텀.

| Fixture | 기본 생성 | 주요 `with` |
|---------|----------|------------|
| `MemberFixture` | `create()`, `createOther()` (다른 email/memberNo) | `withEmail`, `withMemberNo`, `withName`, `withBirthDate`, `withRole`, ... |
| `OrderFixture` | `create(member)` | `withMember`, `withTotalAmount` |
| `BookingFixture` | `create(member, order)` | `withMember`, `withOrder`, `withDepartureStop`, `withArrivalStop` |
| `SeatBookingFixture` | `create(booking)` | `withBooking`, `withSeat`, `withPassengerType` |
| `TicketFixture` | `create(booking)` | — |
| `PaymentFixture` | `create(member, order)` | `withMember`, `withOrder` |
| `ReservationFixture` | `create()` | `withReservationId`, `withMemberNo`, `withTrainScheduleId`, `withOperationDate`, `withDeparture(ReservationStop)`, `withArrival(ReservationStop)`, `withCarType`, `withSeats(List<ReservationSeat>)`, `withSeatIds(trainCarId, seatIds...)`, `withCreatedAt`, `withTtl` |
| `fixture/train/*` | `TrainFixture`, `TrainCarFixture`, `SeatFixture`, `StationFixture.create(name)`, `TrainScheduleFixture`, `ScheduleStopFixture`, `StationFareFixture` — 모두 `create(...)` | — |

```java
Member member = memberRepository.save(MemberFixture.create());
Member other = memberRepository.save(MemberFixture.createOther());
String memberNo = member.getMemberDetail().getMemberNo();
```

`ReservationStop(stopId, stopOrder, stationId, stationName, time)`, `ReservationSeat(seatId, trainCarId, carNumber, seatLabel, passengerType, fare)`.

## 3. Helper (DB에 영속화하는 Spring Bean — `@Autowired`)

### TrainTestHelper
| 메서드 | 결과 |
|--------|------|
| `createKTX()` | 객차 2(일반1·특실1), 좌석 4 |
| `createSmallTestTrain()` | 객차 2, 좌석 36 (일반 24 + 특실 12) |
| `createMediumTestTrain()` | 객차 5(일반3·특실2), 좌석 192 |
| `createCustomKTX(standardRows, firstRows)` | 객차 2, 좌석 = 행 × 2 |
| `createRealisticTrain(stdCars, firstCars, stdRowsPerCar, firstRowsPerCar)` | 다객차 열차 |
| `getSeats(train, carType, count)` | 열차의 좌석 count개 |
| `getAvailableSeats(trainSchedule, carType, count)` | 해당 운행에서 예매되지 않은 좌석 |
| `getSeatIds(train, carType, count)`, `getAllSeatIds(train)`, `getSeatsByIds(ids)` | ID 조회 |

### TrainScheduleTestHelper
| 메서드 | 결과 |
|--------|------|
| `createDefault(train)` | "KTX 001 경부선", 내일, 서울(05:00) → 부산(08:00) |
| `builder().scheduleName(..).operationDate(..).train(..).addStop(station, arrival, departure)...build()` | 커스텀 정차역. `addStop` 순서대로 stopOrder 0부터. 첫 역 arrival=`null`, 마지막 역 departure=`null` |
| `createOrUpdateStationFare(from, to, standardFare, firstClassFare)` | 구간 운임 등록 (중간역 구간 예매 시 필요) |
| `getScheduleStopByStationName(result, "대전")` | 정차역 조회 |
| `getOrCreateStation(name)` | 역 조회/생성 |

결과 `TrainScheduleResult(trainSchedule, scheduleStops)`.

### BookingTestHelper
| 메서드 | 결과 |
|--------|------|
| `createDefault(member, scheduleResult)` | 첫→마지막 역, 일반석 1, 성인 |
| `createByOrderBooking(orderBooking)` | OrderBooking 기반 예매 |
| `builder(member, scheduleResult)` → `setDepartureScheduleStop`, `setArrivalScheduleStop`, `addSeat(seat, type)`, `addSeats(seats, type)`, `addSeatsByCarType(carType, count, type)`, `withoutTickets()` → `build()` | 커스텀 예매 |

결과 `BookingResult(booking, seatBookings, tickets)`. `addSeatsByCarType`은 이미 예매된 좌석을 자동 제외한다.

### OrderTestHelper
| 메서드 | 결과 |
|--------|------|
| `createDefault(member, scheduleResult)` | OrderBooking 1개, 일반석 1, 성인 |
| `builder(member).addOrderBooking(scheduleResult)` → `addSeat`/`addSeats`/`addSeatsByCarType`, `setDepartureScheduleStop`, `setArrivalScheduleStop`, `setReservationId`, `setTotalFare` → `.and()` ... `.build()` | 왕복 등 다중 OrderBooking |

결과 `OrderResult(order, orderBookings, orderSeatBookings)`.

### Redis 계열 (예약 생성 경로)
| Helper | 메서드 | 용도 |
|--------|--------|------|
| `TrainCacheTestHelper` | `seed(train, scheduleResult)` | 좌석·객차·운행·정차역·운임을 Batch와 같은 형식으로 Redis에 적재 |
| | `seedTrain(train)`, `seedSchedule(scheduleId)`, `seedFares()` | 부분 적재 (캐시 누락 시나리오 테스트용) |
| `SeatOccupancyTestHelper` | `markReserved(scheduleId, trainCarId, seatId, depOrder, arrOrder, reservationId)` | 다른 예약의 점유 기록 (만료 없음) |
| | `markBooked(scheduleId, trainCarId, seatId, depOrder, arrOrder, bookingId)` | 예매 점유 기록 |
| | `valueOf(scheduleId, trainCarId, seatId, sectionIndex)` → `"R:..."`/`"B:..."`/`null` | 구간 점유 확인 |
| | `entries(scheduleId, trainCarId)` | 객차 점유 Hash 전체 |
| `ReservationTestHelper` | `save(reservation)` / `save(reservation, ttl)` | 예약 본문 + 회원 인덱스 저장 (점유는 안 함) |
| | `saveBodyOnly(reservation)`, `saveIndexOnly(reservation)` | 불일치 상태 재현 |
| `PaymentReservationTestHelper` | `builder().withTrainScheduleId(..).withDepartureStopId(..).withArrivalStopId(..).withSeats(List<SeatPassenger>)...build()`, `save`, `find`, `delete` | 결제 테스트용 예약 (실제 운임 계산 포함) |

구간 인덱스: `[depOrder, arrOrder)` — 서울(0)→대전(1)→부산(2)에서 서울→부산은 섹션 0, 1.

---

## 4. 레시피

### ① 도메인 단위 테스트 (`raillo-domain`)

```java
@DisplayName("TrainSchedule - getDepartureDateTimeAt 테스트")
class TrainScheduleTest {

	@Test
	@DisplayName("정차역 출발시간이 열차 출발시간보다 이르면 자정을 넘긴 것이므로 다음날을 반환한다")
	void nextDayDeparture_whenStopTimeBeforeTrainDepartureTime() {
		// given - 열차 출발 23:00, 정차역 출발 01:00 (자정 경과)
		LocalDate operationDate = LocalDate.of(2026, 1, 1);
		ScheduleStopTemplate stopTemplate = ScheduleStopTemplate.create(1, LocalTime.of(0, 50), LocalTime.of(1, 0), null);
		TrainSchedule schedule = createSchedule(operationDate, LocalTime.of(23, 0), LocalTime.of(2, 0), stopTemplate);
		ScheduleStop stop = ScheduleStop.create(stopTemplate, schedule);

		// when
		LocalDateTime result = schedule.getDepartureDateTimeAt(stop);

		// then
		assertThat(result).isEqualTo(LocalDateTime.of(2026, 1, 2, 1, 0));
	}

	private TrainSchedule createSchedule(...) { /* 정적 팩토리 조합 */ }
}
```

### ② 서비스 통합 테스트 (DB 중심)

```java
@ServiceTest
class BookingServiceTest {

	@Autowired private BookingService bookingService;
	@Autowired private BookingRepository bookingRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private TrainTestHelper trainTestHelper;
	@Autowired private TrainScheduleTestHelper trainScheduleTestHelper;
	@Autowired private BookingTestHelper bookingTestHelper;

	private Member member;
	private TrainScheduleResult scheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		scheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	@DisplayName("예매를 삭제하면 해당 예매가 더 이상 조회되지 않는다")
	void deleteBooking_success() {
		// given
		BookingResult bookingResult = bookingTestHelper.createDefault(member, scheduleResult);
		Long bookingId = bookingResult.booking().getId();

		// when
		bookingService.deleteBooking(bookingId);

		// then — 반환값이 아니라 저장소를 다시 조회해서 검증
		assertThat(bookingRepository.findById(bookingId)).isEmpty();
	}
}
```

### ③ 예약 생성 경로 (Redis 캐시 + 좌석 점유)

```java
@ServiceTest
class ReservationFacadeTest {

	@BeforeEach
	void setUp() {
		Member member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();
		train = trainTestHelper.createCustomKTX(3, 2);
		// 서울(0, 05:00) → 대전(1, 07:05) → 부산(2, 09:00), 내일 운행
		scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 20000, 35000);
		trainCacheTestHelper.seed(train, scheduleResult);   // ⚠️ 운임 등록 후에 seed

		scheduleId = scheduleResult.trainSchedule().getId();
		standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 3);
	}

	@Nested
	@DisplayName("좌석 충돌")
	class Conflict {

		@Test
		@DisplayName("요청 구간과 겹치는 다른 예약이 있으면 예약할 수 없다")
		void fails_when_overlapping_reservation() {
			// given - 대전→부산(섹션 1)을 다른 예약이 점유
			Seat seat = standardSeats.get(0);
			seatOccupancyTestHelper.markReserved(scheduleId, seat.getTrainCar().getId(), seat.getId(), 1, 2, "OTHER");

			// when & then
			assertThatThrownBy(() -> reservationFacade.createReservation(request(...), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_RESERVATION);
		}
	}
}
```

Redis 값 검증 시 캐시 계약도 함께 본다: JSON에 `@class`가 없어야 하고(`doesNotContain("@class")`), 운행 키 만료는 운행일 기준 절대 시각이다.
