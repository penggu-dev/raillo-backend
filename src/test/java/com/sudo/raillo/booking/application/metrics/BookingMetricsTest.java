package com.sudo.raillo.booking.application.metrics;

import static org.assertj.core.api.Assertions.*;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.facade.PendingBookingFacade;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class BookingMetricsTest {

	@Autowired
	private PendingBookingFacade pendingBookingFacade;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private MeterRegistry meterRegistry;

	private Member member;
	private TrainScheduleResult scheduleResult;
	private List<Seat> seats;
	private Long scheduleId;
	private Long departureStationId;
	private Long arrivalStationId;
	private Long seatId;

	@BeforeEach
	void setup() {
		member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);

		scheduleId = scheduleResult.trainSchedule().getId();
		departureStationId = scheduleResult.scheduleStops().get(0).getStation().getId();
		arrivalStationId = scheduleResult.scheduleStops().get(2).getStation().getId();
		seatId = seats.get(0).getId();
	}

	@Test
	@DisplayName("예약 생성 성공 시 pending_booking_created_total 카운터가 증가한다")
	void createPendingBooking_incrementsPendingCreatedMetric() {
		// given
		String memberNo = "202601010001";
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			scheduleId,
			departureStationId,
			arrivalStationId,
			List.of(PassengerType.ADULT),
			List.of(seatId)
		);

		double before = meterRegistry.counter("pending_booking_created_total").count();

		// when
		pendingBookingFacade.createPendingBooking(request, memberNo);

		// then
		double after = meterRegistry.counter("pending_booking_created_total").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("다른 예약이 점유중인 좌석에 예약하면 seat_conflict 카운터가 증가한다")
	void createPendingBooking_holdConflict_incrementSeatConflictHoldMetric() {
		// given
		// 첫 번째 사용자가 좌석 Hold
		pendingBookingFacade.createPendingBooking(
			new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
				List.of(PassengerType.ADULT), List.of(seatId)),
			"202601010001"
		);

		double before = meterRegistry.counter("seat_conflict_total", "conflict_type", "occupied").count();

		// when
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(
				new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
					List.of(PassengerType.ADULT), List.of(seatId)),
				"202601010002"
			)
		).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(BookingError.SEAT_ALREADY_OCCUPIED);

		// then
		double after = meterRegistry.counter("seat_conflict_total", "conflict_type", "occupied").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("이미 확정된 좌석에 예약을 시도하면 seat_conflict 카운터가 증가한다")
	void createPendingBooking_soldConflict_incrementSeatConflictSoldMetric() {
		// given
		// 좌석을 확정 예매 (DB에 SeatBooking 저장)
		bookingTestHelper.builder(member, scheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		double before = meterRegistry.counter("seat_conflict_total", "conflict_type", "occupied").count();

		// when
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(
				new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
					List.of(PassengerType.ADULT), List.of(seatId)),
				"202601010099"
			)
		).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(BookingError.SEAT_ALREADY_OCCUPIED);

		// then
		double after = meterRegistry.counter("seat_conflict_total", "conflict_type", "occupied").count();
		assertThat(after).isEqualTo(before + 1);
	}


	@Test
	@DisplayName("좌석 충돌로 예약 실패 시 pending_booking_created_total 카운터는 증가하지 않는다")
	void createPendingBooking_soldConflict_doesNotIncrementPendingBookingCreated() {
		// given
		bookingTestHelper.builder(member, scheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		double before = meterRegistry.counter("pending_booking_created_total").count();

		// when
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(
				new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
					List.of(PassengerType.ADULT), List.of(seatId)),
				"202601010001"
			)
		).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(BookingError.SEAT_ALREADY_OCCUPIED);

		// then
		double after = meterRegistry.counter("pending_booking_created_total").count();
		assertThat(after).isEqualTo(before);
	}



	@Test
	@DisplayName("예약 생성 성공 시 예약 생성 타이머와 좌석 점유 타이머가 기록된다")
	void createPendingBooking_success_recordsBothTimers() {
		// given
		double pendingBookingBefore = meterRegistry.timer("pending_booking_duration_seconds").count();
		double seatHoldBefore = meterRegistry.timer("seat_occupancy_duration_seconds").count();

		// when
		pendingBookingFacade.createPendingBooking(
			new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
				List.of(PassengerType.ADULT), List.of(seatId)),
			"202601010001"
		);

		// then
		double pendingBookingAfter = meterRegistry.timer("pending_booking_duration_seconds").count();
		double seatHoldAfter = meterRegistry.timer("seat_occupancy_duration_seconds").count();
		assertThat(pendingBookingAfter).isEqualTo(pendingBookingBefore + 1);
		assertThat(seatHoldAfter).isEqualTo(seatHoldBefore + 1);
	}


	@Test
	@DisplayName("좌석 충돌로 예약에 실패하면 예약 생성 타이머만 기록되고 좌석 점유는 시도되지 않는다")
	void createPendingBooking_seatConflict_failsBeforeOccupancy() {
		// given
		bookingTestHelper.builder(member, scheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		double pendingBookingBefore = meterRegistry.timer("pending_booking_duration_seconds").count();
		double seatHoldBefore = meterRegistry.timer("seat_occupancy_duration_seconds").count();

		// when
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(
				new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
					List.of(PassengerType.ADULT), List.of(seatId)),
				"202601010002"
			)
		).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(BookingError.SEAT_ALREADY_OCCUPIED);

		// then
		double pendingBookingAfter = meterRegistry.timer("pending_booking_duration_seconds").count();
		double seatHoldAfter = meterRegistry.timer("seat_occupancy_duration_seconds").count();
		assertThat(pendingBookingAfter).isEqualTo(pendingBookingBefore + 1);
		// 사전 검증에서 걸러지므로 좌석 점유 삽입은 시도되지 않는다
		assertThat(seatHoldAfter).isEqualTo(seatHoldBefore);
	}
}
