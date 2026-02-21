package com.sudo.raillo.booking.application.facade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldResult;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;

@ServiceTest
class PendingBookingFacadeTest {

	@Autowired
	private PendingBookingFacade pendingBookingFacade;

	@MockitoSpyBean
	private PendingBookingService pendingBookingService;

	@Autowired
	private SeatHoldService seatHoldService;

	@Autowired
	private SeatHoldRepository seatHoldRepository;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Train train;
	private TrainScheduleResult trainScheduleResult;
	private List<Seat> seats;

	@BeforeEach
	void setUp() {
		train = trainTestHelper.createCustomKTX(3, 2);
		trainScheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);
	}

	@Test
	@DisplayName("예약 삭제 시 좌석 Hold가 해제된다")
	void deletePendingBookings_success_holdReleased() {
		// given
		String memberNo = "202601010001";
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
		Long seatId = seats.get(0).getId();

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(new PendingSeatBooking(seatId, PassengerType.ADULT)))
			.build();

		Long trainCarId = seats.get(0).getTrainCar().getId();
		seatHoldService.holdSeats(
			pendingBooking.getId(),
			trainScheduleResult.trainSchedule().getId(),
			departureStop,
			arrivalStop,
			List.of(seatId),
			trainCarId,
			Duration.ofMinutes(10)
		);
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when
		pendingBookingFacade.deletePendingBookings(List.of(pendingBooking.getId()), memberNo);

		// then - Hold가 해제되어 다른 사용자가 같은 좌석을 Hold 할 수 있어야 함
		SeatHoldResult result = seatHoldRepository.tryHold(
			trainScheduleResult.trainSchedule().getId(),
			seatId,
			"other-pending-booking",
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder(),
			trainCarId,
			Duration.ofMinutes(10)
		);
		assertThat(result.success()).isTrue();
	}

	@Test
	@DisplayName("권한이 없는 예약을 삭제하려고 시도하면 예외가 발생한다")
	void deletePendingBookings_fail_notOwner() {
		// given
		String ownerMemberNo = "owner_member_no";
		String nonOwnerMemberNo = "non_owner_member_no";

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(ownerMemberNo)
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when & then
		assertThatThrownBy(() ->
			pendingBookingFacade.deletePendingBookings(
				List.of(pendingBooking.getId()),
				nonOwnerMemberNo
			)).isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.PENDING_BOOKING_ACCESS_DENIED);
	}

	@Test
	@DisplayName("PendingBooking 저장 실패 시 Hold가 롤백된다")
	void createPendingBooking_saveFail_holdRollback() {
		// given
		String memberNo = "202601010001";
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			trainScheduleResult.scheduleStops().get(0).getStation().getId(),
			trainScheduleResult.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		doThrow(new RuntimeException("Redis 저장 실패"))
			.when(pendingBookingService)
			.createPendingBooking(
				anyString(),
				any(),
				any(),
				any(),
				anyList(),
				anyList(),
				anyString(),
				any(BigDecimal.class),
				any(Duration.class)
			);

		// when & then
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(request, memberNo)
		).isInstanceOf(RuntimeException.class)
			.hasMessage("Redis 저장 실패");

		// then - Hold가 롤백되어 다른 사용자가 같은 좌석을 Hold 할 수 있어야 함
		int departureStopOrder = trainScheduleResult.scheduleStops().get(0).getStopOrder();
		int arrivalStopOrder = trainScheduleResult.scheduleStops().get(2).getStopOrder();
		Long trainCarId = seats.get(0).getTrainCar().getId();

		SeatHoldResult result = seatHoldRepository.tryHold(
			trainScheduleResult.trainSchedule().getId(),
			seats.get(0).getId(),
			"other-pending-booking",
			departureStopOrder,
			arrivalStopOrder,
			trainCarId,
			Duration.ofMinutes(10)
		);

		assertThat(result.success()).isTrue();
	}

	@Test
	@DisplayName("출발 시간이 이미 지난 열차를 예약하면 예외가 발생한다")
	void createPendingBooking_fail_departureTimePassed() {
		// given
		TrainScheduleResult pastSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 002 경부선")
			.train(train)
			.operationDate(LocalDate.now().minusDays(1)) // 과거 시간으로 스케줄 생성
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();

		String memberNo = "202601010001";
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			pastSchedule.trainSchedule().getId(),
			pastSchedule.scheduleStops().get(0).getStation().getId(),
			pastSchedule.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		// when & then
		assertThatThrownBy(() -> pendingBookingFacade.createPendingBooking(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainErrorCode.DEPARTURE_TIME_PASSED);
	}

	@Test
	@DisplayName("출발 5분 이내 열차를 예약하면 예외가 발생한다")
	void createPendingBooking_fail_departureWithinFiveMinutes() {
		// given
		LocalDateTime departureDateTime = LocalDateTime.now().plusMinutes(4);
		LocalDateTime arrivalDateTime = departureDateTime.plusHours(1);

		TrainScheduleResult imminentSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 005 임박")
			.train(train)
			.operationDate(departureDateTime.toLocalDate())
			.addStop("서울", null, departureDateTime.toLocalTime())
			.addStop("부산", arrivalDateTime.toLocalTime(), null)
			.build();

		String memberNo = "202601010001";
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			imminentSchedule.trainSchedule().getId(),
			imminentSchedule.scheduleStops().get(0).getStation().getId(),
			imminentSchedule.scheduleStops().get(1).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		// when & then
		assertThatThrownBy(() -> pendingBookingFacade.createPendingBooking(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainErrorCode.DEPARTURE_TIME_PASSED);
	}

	@Test
	@DisplayName("자정을 넘기는 야간 열차의 출발 시간이 아직 지나지 않았으면 예약에 성공한다")
	void createPendingBooking_success_overnightTrain() {
		// given
		TrainScheduleResult overnightSchedule = trainScheduleTestHelper.builder()
			.scheduleName("KTX 003 야간")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(23, 0))
			.addStop("대전", LocalTime.of(0, 30), LocalTime.of(0, 35))
			.addStop("부산", LocalTime.of(2, 0), null)
			.build();
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "부산", 30000, 50000);

		String memberNo = "202601010001";
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			overnightSchedule.trainSchedule().getId(),
			overnightSchedule.scheduleStops().get(1).getStation().getId(),  // 자정을 넘긴 중간역
			overnightSchedule.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		// when & then
		assertThatCode(() -> pendingBookingFacade.createPendingBooking(request, memberNo))
			.doesNotThrowAnyException();
	}
}
