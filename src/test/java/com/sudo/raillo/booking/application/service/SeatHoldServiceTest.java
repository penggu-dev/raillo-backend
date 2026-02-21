package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldResult;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@DisplayName("SeatHoldService 테스트")
class SeatHoldServiceTest {

	@Autowired
	private SeatHoldService seatHoldService;

	@Autowired
	private SeatHoldRepository seatHoldRepository;

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
		seats = trainTestHelper.getSeats(train, CarType.STANDARD, 4);
	}

	@Nested
	@DisplayName("holdSeats - 좌석 임시 점유")
	class HoldSeatsTest {

		@Test
		@DisplayName("좌석 임시 점유에 성공한다")
		void holdSeats_success() {
			// given
			String pendingBookingId = "pending-booking-001";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
			List<Long> seatIds = List.of(seats.get(0).getId(), seats.get(1).getId());
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// when & then
			assertThatCode(() ->
				seatHoldService.holdSeats(
					pendingBookingId,
					trainScheduleId,
					departureStop,
					arrivalStop,
					seatIds,
					trainCarId
				)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("여러 좌석 임시 점유에 성공한다")
		void holdSeats_multipleSeats_success() {
			// given
			String pendingBookingId = "pending-booking-001";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);
			List<Long> seatIds = List.of(
				seats.get(0).getId(),
				seats.get(1).getId(),
				seats.get(2).getId()
			);
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// when & then
			assertThatCode(() ->
				seatHoldService.holdSeats(
					pendingBookingId,
					trainScheduleId,
					departureStop,
					arrivalStop,
					seatIds,
					trainCarId
				)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("다른 사용자가 Hold 중인 좌석 점유 시도 시 충돌 예외가 발생한다")
		void holdSeats_conflictWithHold_fail() {
			// given
			String pendingBookingId1 = "pending-booking-001";
			String pendingBookingId2 = "pending-booking-002";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
			List<Long> seatIds = List.of(seats.get(0).getId());
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// 첫 번째 사용자가 먼저 Hold
			seatHoldService.holdSeats(
				pendingBookingId1,
				trainScheduleId,
				departureStop,
				arrivalStop,
				seatIds,
				trainCarId
			);

			// when & then - 두 번째 사용자가 같은 좌석 Hold 시도
			assertThatThrownBy(() ->
				seatHoldService.holdSeats(
					pendingBookingId2,
					trainScheduleId,
					departureStop,
					arrivalStop,
					seatIds,
					trainCarId
				)
			)
				.isInstanceOf(BusinessException.class)
				.hasMessage(BookingError.SEAT_CONFLICT_WITH_HOLD.getMessage());
		}

		@Test
		@DisplayName("여러 좌석 중 하나라도 충돌 시 전체 롤백된다")
		void holdSeats_multipleSeats_rollbackOnConflict() {
			// given
			String pendingBookingId1 = "pending-booking-001";
			String pendingBookingId2 = "pending-booking-002";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
			int departureStopOrder = departureStop.getStopOrder();
			int arrivalStopOrder = arrivalStop.getStopOrder();

			Long seat1Id = seats.get(0).getId();
			Long seat2Id = seats.get(1).getId();
			Long seat3Id = seats.get(2).getId();
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// 좌석 2번에 먼저 Hold
			seatHoldRepository.tryHold(trainScheduleId, seat2Id, pendingBookingId1, departureStopOrder, arrivalStopOrder, trainCarId);

			// when - 좌석 1, 2, 3 동시 Hold 시도 (2번에서 충돌)
			assertThatThrownBy(() ->
				seatHoldService.holdSeats(
					pendingBookingId2,
					trainScheduleId,
					departureStop,
					arrivalStop,
					List.of(seat1Id, seat2Id, seat3Id),
					trainCarId
				)
			)
				.isInstanceOf(BusinessException.class)
				.hasMessage(BookingError.SEAT_CONFLICT_WITH_HOLD.getMessage());

			// then - 좌석 1번도 롤백되어 Hold 가능해야 함
			SeatHoldResult result = seatHoldRepository.tryHold(
				trainScheduleId, seat1Id, "pending-booking-003", departureStopOrder, arrivalStopOrder, trainCarId
			);
			assertThat(result.success()).isTrue();
		}

		@Test
		@DisplayName("겹치지 않는 구간은 같은 좌석이라도 Hold 가능하다")
		void holdSeats_nonOverlappingSections_success() {
			// given
			String pendingBookingId1 = "pending-booking-001";
			String pendingBookingId2 = "pending-booking-002";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			Long seatId = seats.get(0).getId();
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// 첫 번째 사용자: 서울 -> 대전 (구간 0-1)
			ScheduleStop departureStop1 = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop1 = trainScheduleResult.scheduleStops().get(1);

			// 두 번째 사용자: 대전 -> 부산 (구간 1-2)
			ScheduleStop departureStop2 = trainScheduleResult.scheduleStops().get(1);
			ScheduleStop arrivalStop2 = trainScheduleResult.scheduleStops().get(2);

			// 첫 번째 사용자 Hold
			seatHoldService.holdSeats(
				pendingBookingId1,
				trainScheduleId,
				departureStop1,
				arrivalStop1,
				List.of(seatId),
				trainCarId
			);

			// when & then - 두 번째 사용자도 Hold 성공 (구간이 겹치지 않음)
			assertThatCode(() ->
				seatHoldService.holdSeats(
					pendingBookingId2,
					trainScheduleId,
					departureStop2,
					arrivalStop2,
					List.of(seatId),
					trainCarId
				)
			).doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("releaseSeats - 좌석 점유 해제")
	class ReleaseSeatsTest {

		@Test
		@DisplayName("좌석 점유 해제에 성공한다")
		void releaseSeats_success() {
			// given
			String pendingBookingId = "pending-booking-001";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
			List<Long> seatIds = List.of(seats.get(0).getId(), seats.get(1).getId());
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// Hold 먼저 수행
			seatHoldService.holdSeats(
				pendingBookingId,
				trainScheduleId,
				departureStop,
				arrivalStop,
				seatIds,
				trainCarId
			);

			// when & then
			assertThatCode(() ->
				seatHoldService.releaseSeats(
					pendingBookingId,
					trainScheduleId,
					seatIds,
					trainCarId,
					departureStop.getStopOrder(),
					arrivalStop.getStopOrder()
				)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("점유 해제 후 같은 좌석을 다시 Hold 할 수 있다")
		void releaseSeats_thenHoldAgain_success() {
			// given
			String pendingBookingId1 = "pending-booking-001";
			String pendingBookingId2 = "pending-booking-002";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
			List<Long> seatIds = List.of(seats.get(0).getId());
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// 첫 번째 사용자 Hold
			seatHoldService.holdSeats(
				pendingBookingId1,
				trainScheduleId,
				departureStop,
				arrivalStop,
				seatIds,
				trainCarId
			);

			// 첫 번째 사용자 Release
			seatHoldService.releaseSeats(
				pendingBookingId1,
				trainScheduleId,
				seatIds,
				trainCarId,
				departureStop.getStopOrder(),
				arrivalStop.getStopOrder()
			);

			// when & then - 두 번째 사용자가 같은 좌석 Hold 성공
			assertThatCode(() ->
				seatHoldService.holdSeats(
					pendingBookingId2,
					trainScheduleId,
					departureStop,
					arrivalStop,
					seatIds,
					trainCarId
				)
			).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("여러 좌석 점유 해제에 성공한다")
		void releaseSeats_multipleSeats_success() {
			// given
			String pendingBookingId = "pending-booking-001";
			Long trainScheduleId = trainScheduleResult.trainSchedule().getId();
			ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
			ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(2);
			List<Long> seatIds = List.of(
				seats.get(0).getId(),
				seats.get(1).getId(),
				seats.get(2).getId()
			);
			Long trainCarId = seats.get(0).getTrainCar().getId();

			// Hold 먼저 수행
			seatHoldService.holdSeats(
				pendingBookingId,
				trainScheduleId,
				departureStop,
				arrivalStop,
				seatIds,
				trainCarId
			);

			// when & then
			assertThatCode(() ->
				seatHoldService.releaseSeats(
					pendingBookingId,
					trainScheduleId,
					seatIds,
					trainCarId,
					departureStop.getStopOrder(),
					arrivalStop.getStopOrder()
				)
			).doesNotThrowAnyException();

			// 해제 후 다시 Hold 가능 확인
			SeatHoldResult result = seatHoldRepository.tryHold(
				trainScheduleId,
				seatIds.get(0),
				"new-pending",
				departureStop.getStopOrder(),
				arrivalStop.getStopOrder(),
				trainCarId
			);
			assertThat(result.success()).isTrue();
		}
	}
}
