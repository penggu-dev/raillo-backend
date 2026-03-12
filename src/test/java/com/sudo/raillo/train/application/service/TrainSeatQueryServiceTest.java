package com.sudo.raillo.train.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.application.dto.TrainCarSeatInfo;
import com.sudo.raillo.train.application.dto.projection.SeatProjection;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.TrainCarRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class TrainSeatQueryServiceTest {

	@Autowired
	private TrainSeatQueryService trainSeatQueryService;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainCarRepository trainCarRepository;

	private TrainScheduleResult trainScheduleResult;
	private ScheduleStop departureStop;
	private ScheduleStop arrivalStop;
	private Train train;

	@BeforeEach
	void setUp() {
		train = trainTestHelper.createCustomKTX(1, 1);
		trainScheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("test-schedule")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("서울", null, LocalTime.of(9, 30))
			.addStop("대전", LocalTime.of(10, 30), LocalTime.of(10, 32))
			.addStop("부산", LocalTime.of(12, 30), null)
			.build();

		departureStop = trainScheduleTestHelper.getScheduleStopByStationName(trainScheduleResult, "서울");
		arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(trainScheduleResult, "부산");
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 10000);
	}

	@Test
	@DisplayName("잔여 좌석이 있는 객차 목록을 성공적으로 조회한다")
	void getAvailableTrainCars() {
		// when
		List<TrainCarInfo> availableTrainCars = trainSeatQueryService.getAvailableTrainCars(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId()
		);

		// then
		assertThat(availableTrainCars).hasSize(2);

		TrainCarInfo standardCar = availableTrainCars.get(0);
		assertThat(standardCar.id()).isEqualTo(1L);
		assertThat(standardCar.carNumber()).isEqualTo("0001");
		assertThat(standardCar.carType()).isEqualTo(CarType.STANDARD);
		assertThat(standardCar.totalSeats()).isEqualTo(2);
		assertThat(standardCar.remainingSeats()).isEqualTo(2);

		TrainCarInfo firstClassCar = availableTrainCars.get(1);
		assertThat(firstClassCar.id()).isEqualTo(2L);
		assertThat(firstClassCar.carNumber()).isEqualTo("0002");
		assertThat(firstClassCar.carType()).isEqualTo(CarType.FIRST_CLASS);
		assertThat(firstClassCar.totalSeats()).isEqualTo(2);
		assertThat(firstClassCar.remainingSeats()).isEqualTo(2);
	}

	@Test
	@DisplayName("예약된 좌석이 있으면 해당 좌석은 조회 되지 않는다")
	void shouldExcludeBookedSeatsFromAvailableCount() {
		// given
		Member testMember = memberRepository.save(MemberFixture.create());
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		bookingTestHelper.builder(testMember, trainScheduleResult)
			.setDepartureScheduleStop(departureStop)
			.setArrivalScheduleStop(arrivalStop)
			.addSeats(standardSeats, PassengerType.ADULT)
			.build();

		// when
		List<TrainCarInfo> availableTrainCars = trainSeatQueryService.getAvailableTrainCars(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId()
		);

		// then
		TrainCarInfo standardCar = availableTrainCars.get(0);
		assertThat(standardCar.carType()).isEqualTo(CarType.STANDARD);
		assertThat(standardCar.totalSeats()).isEqualTo(2);
		assertThat(standardCar.remainingSeats()).isEqualTo(1);
	}

	@Test
	@DisplayName("잔여 좌석이 없으면 조회 되지 않는다")
	void shouldThrowExceptionWhenNoAvailableSeats() {
		// given
		Member testMember = memberRepository.save(MemberFixture.create());
		List<Seat> allSeats = new ArrayList<>();
		allSeats.addAll(trainTestHelper.getSeats(train, CarType.STANDARD, 2));
		allSeats.addAll(trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 2));

		bookingTestHelper.builder(testMember, trainScheduleResult)
			.setDepartureScheduleStop(departureStop)
			.setArrivalScheduleStop(arrivalStop)
			.addSeats(allSeats, PassengerType.ADULT)
			.build();

		// when & then
		assertThatThrownBy(() -> trainSeatQueryService.getAvailableTrainCars(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId()
		))
			.isInstanceOf(BusinessException.class)
			.hasMessage(TrainErrorCode.NO_AVAILABLE_CARS.getMessage());
	}

	@Test
	@DisplayName("객차 좌석 상세 정보를 성공적으로 조회한다")
	void getTrainCarSeatDetail() {
		// given
		List<TrainCar> trainCars = trainCarRepository.findByTrainIn(List.of(train));
		TrainCar trainCar = trainCars.get(0);
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(
			trainCar.getId(),
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId()
		);

		// when
		TrainCarSeatInfo result = trainSeatQueryService.getTrainCarSeatDetail(request);

		// then
		assertThat(result.carNumber()).isEqualTo("1");
		assertThat(result.carType()).isEqualTo(CarType.STANDARD);
		assertThat(result.totalSeats()).isEqualTo(2);
		assertThat(result.remainingSeats()).isEqualTo(2);
		assertThat(result.getLayoutType()).isEqualTo(2);
		assertThat(result.seats()).hasSize(2);

		SeatProjection firstSeat = result.seats().get(0);
		assertThat(firstSeat.getSeatNumber()).isEqualTo("1A");
		assertThat(firstSeat.isAvailable()).isTrue();

		SeatProjection secondSeat = result.seats().get(1);
		assertThat(secondSeat.getSeatNumber()).isEqualTo("1B");
		assertThat(secondSeat.isAvailable()).isTrue();
	}

	@Test
	@DisplayName("예약된 좌석은 조회시 사용 불가능한 상태로 조회된다.")
	void shouldBookedSeatsAsUnavailable() {
		// given
		Member testMember = memberRepository.save(MemberFixture.create());
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		Seat bookedSeat = standardSeats.get(0);
		String bookedSeatNumber = bookedSeat.getSeatRow() + bookedSeat.getSeatColumn();

		bookingTestHelper.builder(testMember, trainScheduleResult)
			.setDepartureScheduleStop(departureStop)
			.setArrivalScheduleStop(arrivalStop)
			.addSeats(standardSeats, PassengerType.ADULT)
			.build();

		TrainCar trainCar = trainCarRepository.findByTrainIn(List.of(train)).stream()
			.filter(car -> car.getCarType() == CarType.STANDARD)
			.findFirst()
			.orElseThrow();
		TrainCarSeatDetailRequest request = new TrainCarSeatDetailRequest(
			trainCar.getId(),
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId()
		);

		// when
		TrainCarSeatInfo response = trainSeatQueryService.getTrainCarSeatDetail(request);

		// then
		SeatProjection bookedSeatProjection = response.seats().stream()
			.filter(seat -> seat.getSeatNumber().equals(bookedSeatNumber))
			.findFirst()
			.orElseThrow();

		long unavailableSeatCount = response.seats().stream()
			.filter(seat -> !seat.isAvailable())
			.count();

		assertThat(response.remainingSeats()).isEqualTo(1);
		assertThat(bookedSeatProjection.isAvailable()).isFalse();
		assertThat(unavailableSeatCount).isEqualTo(1);
	}
}
