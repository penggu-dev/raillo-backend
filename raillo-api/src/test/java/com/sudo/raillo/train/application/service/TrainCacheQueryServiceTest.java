package com.sudo.raillo.train.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainCacheTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.ReservationTrainContext;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;

@ServiceTest
class TrainCacheQueryServiceTest {

	@Autowired
	private TrainCacheQueryService trainCacheQueryService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCacheTestHelper trainCacheTestHelper;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	private Train train;
	private TrainScheduleResult scheduleResult;
	private Station seoul;
	private Station busan;
	private List<Seat> standardSeats;

	@BeforeEach
	void setUp() {
		train = trainTestHelper.createKTX();
		scheduleResult = trainScheduleTestHelper.createDefault(train);
		seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		busan = trainScheduleTestHelper.getOrCreateStation("부산");
		standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		trainCacheTestHelper.seed(train, scheduleResult);
	}

	@Nested
	@DisplayName("정상 조회")
	class Success {

		@Test
		@DisplayName("캐시에 있는 운행·정차역·좌석·운임을 하나의 컨텍스트로 돌려준다")
		void returnsContext() {
			// given
			List<Long> seatIds = standardSeats.stream().map(Seat::getId).toList();

			// when
			ReservationTrainContext context = trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), busan.getId(), seatIds);

			// then
			assertThat(context.schedule().trainScheduleId()).isEqualTo(scheduleResult.trainSchedule().getId());
			assertThat(context.departureStop().stationName()).isEqualTo("서울");
			assertThat(context.arrivalStop().stationName()).isEqualTo("부산");
			assertThat(context.seatsById().keySet()).containsExactlyElementsOf(seatIds);
			assertThat(context.seatsById().values()).allMatch(seat -> seat.carType() == CarType.STANDARD);
			assertThat(context.fare().standardFare()).isEqualByComparingTo("50000");
			assertThat(context.fare().firstClassFare()).isEqualByComparingTo("100000");
		}

		@Test
		@DisplayName("출발 일시는 운행일과 출발 정차역의 출발 시각을 합친 값이다")
		void departureAtUsesStopDepartureTime() {
			// given
			ScheduleStop departureStop = scheduleResult.scheduleStops().get(0);
			LocalDate operationDate = scheduleResult.trainSchedule().getOperationDate();

			// when
			ReservationTrainContext context = trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), busan.getId(),
				List.of(standardSeats.get(0).getId()));

			// then
			assertThat(context.departureAt()).isEqualTo(LocalDateTime.of(operationDate, departureStop.getDepartureTime()));
		}

		@Test
		@DisplayName("정차역 출발 시각이 열차 출발 시각보다 이르면 자정을 넘긴 것이므로 다음 날로 계산한다")
		void departureAtRollsOverMidnight() {
			// given - 서울 23:30 출발, 대전 00:30 출발
			TrainScheduleResult overnight = trainScheduleTestHelper.builder()
				.train(train)
				.operationDate(LocalDate.of(2026, 10, 20))
				.addStop("서울", null, LocalTime.of(23, 30))
				.addStop("대전", LocalTime.of(0, 25), LocalTime.of(0, 30))
				.addStop("부산", LocalTime.of(2, 0), null)
				.build();
			Station daejeon = trainScheduleTestHelper.getOrCreateStation("대전");
			trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 30000, 60000);
			trainCacheTestHelper.seed(train, overnight);

			// when
			ReservationTrainContext context = trainCacheQueryService.getReservationContext(
				overnight.trainSchedule().getId(), daejeon.getId(), busan.getId(),
				List.of(standardSeats.get(0).getId()));

			// then
			assertThat(context.departureAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 0, 30));
		}
	}

	@Nested
	@DisplayName("캐시 누락")
	class Missing {

		@Test
		@DisplayName("운행 정보가 없으면 TRAIN_SCHEDULE_NOT_FOUND 예외가 발생한다")
		void scheduleMissing() {
			// given
			long unknownScheduleId = scheduleResult.trainSchedule().getId() + 1000;

			// when

			// then
			assertThatThrownBy(() -> trainCacheQueryService.getReservationContext(
				unknownScheduleId, seoul.getId(), busan.getId(), List.of(standardSeats.get(0).getId())))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.TRAIN_SCHEDULE_NOT_FOUND);
		}

		@Test
		@DisplayName("운행에 없는 역을 요청하면 STATION_NOT_FOUND 예외가 발생한다")
		void stopMissing() {
			// given
			Station daegu = trainScheduleTestHelper.getOrCreateStation("동대구");

			// when

			// then
			assertThatThrownBy(() -> trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), daegu.getId(),
				List.of(standardSeats.get(0).getId())))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.STATION_NOT_FOUND);
		}

		@Test
		@DisplayName("좌석 하나라도 캐시에 없으면 SEAT_NOT_FOUND 예외가 발생한다")
		void seatMissing() {
			// given
			List<Long> seatIds = List.of(standardSeats.get(0).getId(), 999_999L);

			// when

			// then
			assertThatThrownBy(() -> trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), busan.getId(), seatIds))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.SEAT_NOT_FOUND);
		}

		@Test
		@DisplayName("요청 운행의 열차에 속하지 않은 좌석이면 SEAT_NOT_FOUND 예외가 발생한다")
		void seat_of_other_train() {
			// given
			Train otherTrain = trainTestHelper.createKTX();
			trainCacheTestHelper.seedTrain(otherTrain);
			Seat otherSeat = trainTestHelper.getSeats(otherTrain, CarType.STANDARD, 1).get(0);

			// when

			// then
			assertThatThrownBy(() -> trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), busan.getId(), List.of(otherSeat.getId())))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.SEAT_NOT_FOUND);
		}

		@Test
		@DisplayName("좌석의 객차 정보가 캐시에 없으면 TRAIN_CAR_NOT_FOUND 예외가 발생한다")
		void train_car_missing() {
			// given
			Seat seat = standardSeats.get(0);
			stringRedisTemplate.delete(TrainCacheKey.trainCar(seat.getTrainCar().getId()));

			// when

			// then
			assertThatThrownBy(() -> trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), busan.getId(), List.of(seat.getId())))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", TrainError.TRAIN_CAR_NOT_FOUND);
		}

		@Test
		@DisplayName("구간 운임이 없으면 예외 없이 운임 자리를 null로 돌려준다")
		void fareMissing() {
			// given
			stringRedisTemplate.opsForHash().delete(TrainCacheKey.fare(),
				TrainCacheKey.fareField(seoul.getId(), busan.getId()));

			// when
			ReservationTrainContext context = trainCacheQueryService.getReservationContext(
				scheduleResult.trainSchedule().getId(), seoul.getId(), busan.getId(),
				List.of(standardSeats.get(0).getId()));

			// then
			assertThat(context.fare()).isNull();
			assertThat(context.departureStop().stationName()).isEqualTo("서울");
		}
	}
}
