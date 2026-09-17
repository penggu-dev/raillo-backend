package com.sudo.raillo.booking.application.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.util.ReservationIdGenerator;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;
import com.sudo.raillo.support.helper.TrainCacheTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

@ServiceTest
@DisplayName("ReservationFacade - 예약 생성")
class ReservationFacadeTest {

	@Autowired
	private ReservationFacade reservationFacade;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCacheTestHelper trainCacheTestHelper;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancyTestHelper;

	@Autowired
	private TrainScheduleRepository trainScheduleRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private RedisJsonConverter redisJsonConverter;

	private String memberNo;
	private Train train;
	private TrainScheduleResult scheduleResult;
	private Long scheduleId;
	private Long seoulId;
	private Long daejeonId;
	private Long busanId;
	private List<Seat> standardSeats;

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
		trainCacheTestHelper.seed(train, scheduleResult);

		scheduleId = scheduleResult.trainSchedule().getId();
		seoulId = scheduleResult.scheduleStops().get(0).getStation().getId();
		daejeonId = scheduleResult.scheduleStops().get(1).getStation().getId();
		busanId = scheduleResult.scheduleStops().get(2).getStation().getId();
		standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 3);
	}

	private ReservationCreateRequest request(Long departureStationId, Long arrivalStationId,
		List<PassengerType> passengerTypes, List<Long> seatIds) {
		return new ReservationCreateRequest(scheduleId, departureStationId, arrivalStationId, passengerTypes, seatIds);
	}

	private Reservation storedReservation(String reservationId) {
		String json = stringRedisTemplate.opsForValue().get(ReservationCacheKey.reservation(scheduleId, reservationId));
		assertThat(json).isNotNull().doesNotContain("@class");
		return redisJsonConverter.fromJson(json, Reservation.class);
	}

	@Nested
	@DisplayName("성공")
	class Success {

		@Test
		@DisplayName("예약이 생성되면 표시용 정보가 담긴 본문, 구간별 좌석 점유, 회원 인덱스가 Redis에 남는다")
		void createsReservation() {
			// given
			Seat seatA = standardSeats.get(0);
			Seat seatB = standardSeats.get(1);
			ReservationCreateRequest request = request(seoulId, busanId,
				List.of(PassengerType.ADULT, PassengerType.CHILD), List.of(seatA.getId(), seatB.getId()));
			LocalDateTime before = LocalDateTime.now();

			// when
			ReservationCreateResponse response = reservationFacade.createReservation(request, memberNo);

			// then
			assertThat(response.reservationId()).hasSize(ReservationIdGenerator.LENGTH).startsWith("RV");
			assertThat(response.expiresAt()).isBetween(before.plusMinutes(10).minusSeconds(2), before.plusMinutes(10).plusSeconds(2));

			Reservation stored = storedReservation(response.reservationId());
			assertThat(stored.memberNo()).isEqualTo(memberNo);
			assertThat(stored.trainNumber()).isEqualTo(train.getTrainNumber());
			assertThat(stored.trainName()).isEqualTo(train.getTrainName());
			assertThat(stored.operationDate()).isEqualTo(LocalDate.now().plusDays(1));
			assertThat(stored.departure().stationName()).isEqualTo("서울");
			assertThat(stored.departure().stopOrder()).isZero();
			assertThat(stored.departure().time()).isEqualTo(LocalTime.of(5, 0));
			assertThat(stored.arrival().stationName()).isEqualTo("부산");
			assertThat(stored.arrival().stopOrder()).isEqualTo(2);
			assertThat(stored.arrival().time()).isEqualTo(LocalTime.of(9, 0));
			assertThat(stored.carType()).isEqualTo(CarType.STANDARD);
			assertThat(stored.expiresAt()).isEqualTo(response.expiresAt());
			assertThat(stored.seats()).extracting(ReservationSeat::seatId).containsExactly(seatA.getId(), seatB.getId());
			assertThat(stored.seats().get(0).seatLabel()).isEqualTo(seatA.getSeatRow() + seatA.getSeatColumn());
			assertThat(stored.seats().get(0).carNumber()).isEqualTo(seatA.getTrainCar().getCarNumber());
			assertThat(stored.seats().get(0).fare()).isEqualByComparingTo("30000");
			assertThat(stored.seats().get(1).passengerType()).isEqualTo(PassengerType.CHILD);
			assertThat(stored.seats().get(1).fare()).isEqualByComparingTo("18000");
			assertThat(stored.totalFare()).isEqualByComparingTo("48000");

			long carId = seatA.getTrainCar().getId();
			String hold = "H:" + response.reservationId();
			assertThat(seatOccupancyTestHelper.valueOf(scheduleId, carId, seatA.getId(), 0)).isEqualTo(hold);
			assertThat(seatOccupancyTestHelper.valueOf(scheduleId, carId, seatA.getId(), 1)).isEqualTo(hold);
			assertThat(seatOccupancyTestHelper.valueOf(scheduleId, carId, seatB.getId(), 1)).isEqualTo(hold);
			assertThat(seatOccupancyTestHelper.entries(scheduleId, carId)).hasSize(4);
			assertThat(stringRedisTemplate.opsForHash().get(ReservationCacheKey.memberReservations(memberNo), response.reservationId()))
				.isEqualTo(String.valueOf(scheduleId));
			assertThat(stringRedisTemplate.getExpire(ReservationCacheKey.reservation(scheduleId, response.reservationId()), TimeUnit.SECONDS))
				.isBetween(590L, 600L);
		}

		@Test
		@DisplayName("객차가 달라도 객차 타입이 같으면 한 예약으로 만들 수 있다")
		void allowsSeatsAcrossCarsOfSameType() {
			// given - 일반실 3량 열차에서 서로 다른 객차의 좌석 두 개
			Train mediumTrain = trainTestHelper.createMediumTestTrain();
			TrainScheduleResult mediumSchedule = trainScheduleTestHelper.builder()
				.train(mediumTrain)
				.operationDate(LocalDate.now().plusDays(1))
				.addStop("서울", null, LocalTime.of(10, 0))
				.addStop("부산", LocalTime.of(13, 0), null)
				.build();
			trainCacheTestHelper.seed(mediumTrain, mediumSchedule);
			List<Seat> standard = trainTestHelper.getSeats(mediumTrain, CarType.STANDARD, 200);
			Seat first = standard.get(0);
			Seat other = standard.stream()
				.filter(s -> !s.getTrainCar().getId().equals(first.getTrainCar().getId()))
				.findFirst().orElseThrow();
			ReservationCreateRequest request = new ReservationCreateRequest(mediumSchedule.trainSchedule().getId(),
				seoulId, busanId, List.of(PassengerType.ADULT, PassengerType.ADULT), List.of(first.getId(), other.getId()));

			// when
			ReservationCreateResponse response = reservationFacade.createReservation(request, memberNo);

			// then
			long mediumScheduleId = mediumSchedule.trainSchedule().getId();
			assertThat(seatOccupancyTestHelper.valueOf(mediumScheduleId, first.getTrainCar().getId(), first.getId(), 0))
				.isEqualTo("H:" + response.reservationId());
			assertThat(seatOccupancyTestHelper.valueOf(mediumScheduleId, other.getTrainCar().getId(), other.getId(), 0))
				.isEqualTo("H:" + response.reservationId());
		}

		@Test
		@DisplayName("같은 좌석이라도 겹치지 않는 구간은 다른 예약이 있어도 예약할 수 있다")
		void allowsNonOverlappingSection() {
			// given - 다른 예약이 서울→대전(구간 0) 점유
			Seat seat = standardSeats.get(0);
			seatOccupancyTestHelper.markHeld(scheduleId, seat.getTrainCar().getId(), seat.getId(), 0, 1, "OTHER");

			// when - 대전→부산(구간 1)
			ReservationCreateResponse response = reservationFacade.createReservation(
				request(daejeonId, busanId, List.of(PassengerType.ADULT), List.of(seat.getId())), memberNo);

			// then
			Reservation stored = storedReservation(response.reservationId());
			assertThat(stored.departure().stationName()).isEqualTo("대전");
			assertThat(stored.totalFare()).isEqualByComparingTo("20000");
			assertThat(seatOccupancyTestHelper.valueOf(scheduleId, seat.getTrainCar().getId(), seat.getId(), 1))
				.isEqualTo("H:" + response.reservationId());
		}
	}

	@Nested
	@DisplayName("좌석 충돌")
	class Conflict {

		@Test
		@DisplayName("다른 사용자가 점유 중인 구간이면 SEAT_CONFLICT_WITH_HOLD 예외가 발생한다")
		void conflictWithHold() {
			// given
			Seat seat = standardSeats.get(0);
			seatOccupancyTestHelper.markHeld(scheduleId, seat.getTrainCar().getId(), seat.getId(), 1, 2, "OTHER");

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, busanId, List.of(PassengerType.ADULT), List.of(seat.getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_HOLD);
			assertThat(stringRedisTemplate.opsForHash().size(ReservationCacheKey.memberReservations(memberNo))).isZero();
		}

		@Test
		@DisplayName("이미 판매된 구간이면 SEAT_CONFLICT_WITH_SOLD 예외가 발생한다")
		void conflictWithSold() {
			// given
			Seat seat = standardSeats.get(0);
			seatOccupancyTestHelper.markSold(scheduleId, seat.getTrainCar().getId(), seat.getId(), 0, 2, "77");

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(daejeonId, busanId, List.of(PassengerType.ADULT), List.of(seat.getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_CONFLICT_WITH_SOLD);
		}
	}

	@Nested
	@DisplayName("검증 실패")
	class ValidationFailure {

		@Test
		@DisplayName("기준정보 캐시가 비어 있으면 TRAIN_SCHEDULE_NOT_FOUND 예외가 발생한다")
		void cacheMissing() {
			// given
			stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, busanId, List.of(PassengerType.ADULT), List.of(standardSeats.get(0).getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.TRAIN_SCHEDULE_NOT_FOUND);
		}

		@Test
		@DisplayName("운행이 취소된 스케줄이면 TRAIN_OPERATION_CANCELLED 예외가 발생한다")
		void cancelledSchedule() {
			// given
			TrainSchedule schedule = trainScheduleRepository.findById(scheduleId).orElseThrow();
			schedule.updateOperationStatus(OperationStatus.CANCELLED);
			trainScheduleRepository.save(schedule);
			trainCacheTestHelper.seedSchedule(scheduleId);

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, busanId, List.of(PassengerType.ADULT), List.of(standardSeats.get(0).getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.TRAIN_OPERATION_CANCELLED);
		}

		@Test
		@DisplayName("출발 시각이 지난 운행이면 DEPARTURE_TIME_PASSED 예외가 발생한다")
		void departurePassed() {
			// given - 어제 05:00 출발
			TrainScheduleResult yesterday = trainScheduleTestHelper.builder()
				.train(train)
				.operationDate(LocalDate.now().minusDays(1))
				.addStop("서울", null, LocalTime.of(5, 0))
				.addStop("부산", LocalTime.of(8, 0), null)
				.build();
			trainCacheTestHelper.seedSchedule(yesterday.trainSchedule().getId());
			ReservationCreateRequest request = new ReservationCreateRequest(yesterday.trainSchedule().getId(),
				seoulId, busanId, List.of(PassengerType.ADULT), List.of(standardSeats.get(0).getId()));

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.DEPARTURE_TIME_PASSED);
		}

		@Test
		@DisplayName("출발역과 도착역이 같으면 INVALID_ROUTE 예외가 발생한다")
		void sameStation() {
			// given

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, seoulId, List.of(PassengerType.ADULT), List.of(standardSeats.get(0).getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.INVALID_ROUTE);
		}

		@Test
		@DisplayName("승객 수와 좌석 수가 다르면 BOOKING_CREATE_SEATS_INVALID 예외가 발생한다")
		void passengerSeatMismatch() {
			// given

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, busanId, List.of(PassengerType.ADULT, PassengerType.ADULT), List.of(standardSeats.get(0).getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.BOOKING_CREATE_SEATS_INVALID);
		}

		@Test
		@DisplayName("같은 좌석을 두 번 고르면 DUPLICATE_SEAT_IDS 예외가 발생한다")
		void duplicateSeat() {
			// given
			Long seatId = standardSeats.get(0).getId();

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, busanId, List.of(PassengerType.ADULT, PassengerType.ADULT), List.of(seatId, seatId)), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.DUPLICATE_SEAT_IDS);
		}

		@Test
		@DisplayName("일반실과 특실 좌석을 섞으면 INVALID_CAR_TYPE 예외가 발생한다")
		void mixedCarTypes() {
			// given
			Seat firstClass = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1).get(0);

			// when

			// then
			assertThatThrownBy(() -> reservationFacade.createReservation(
				request(seoulId, busanId, List.of(PassengerType.ADULT, PassengerType.ADULT),
					List.of(standardSeats.get(0).getId(), firstClass.getId())), memberNo))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", BookingError.INVALID_CAR_TYPE);
		}
	}
}
