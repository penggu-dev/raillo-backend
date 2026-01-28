package com.sudo.raillo.booking.application.facade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
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
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class PendingBookingFacadeTest {

	@Autowired
	private PendingBookingFacade pendingBookingFacade;

	@MockitoSpyBean
	private PendingBookingService pendingBookingService;

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

	// PendingBookingFacadeTest로 이동
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
				any(BigDecimal.class)
			);

		// when & then
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(request, memberNo)
		).isInstanceOf(RuntimeException.class)
			.hasMessage("Redis 저장 실패");

		// then - Hold가 롤백되어 다른 사용자가 같은 좌석을 Hold 할 수 있어야 함
		int departureStopOrder = trainScheduleResult.scheduleStops().get(0).getStopOrder();
		int arrivalStopOrder = trainScheduleResult.scheduleStops().get(2).getStopOrder();

		SeatHoldResult result = seatHoldRepository.tryHold(
			trainScheduleResult.trainSchedule().getId(),
			seats.get(0).getId(),
			"other-pending-booking",
			departureStopOrder,
			arrivalStopOrder
		);

		assertThat(result.success()).isTrue();
	}
}
