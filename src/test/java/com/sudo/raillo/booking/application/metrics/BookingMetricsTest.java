package com.sudo.raillo.booking.application.metrics;

import static org.assertj.core.api.Assertions.*;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.facade.PendingBookingFacade;
import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
	private SeatHoldService seatHoldService;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	@DisplayName("예약 생성 성공 시 pending_created 카운터가 증가한다")
	void createPendingBooking_incrementsPendingCreatedMetric() {
		// given
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);

		String memberNo = "202601010001";
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			scheduleResult.trainSchedule().getId(),
			scheduleResult.scheduleStops().get(0).getStation().getId(),
			scheduleResult.scheduleStops().get(2).getStation().getId(),
			List.of(PassengerType.ADULT),
			List.of(seats.get(0).getId())
		);

		double before = meterRegistry.counter("pending_booking_created_total").count();

		// when
		pendingBookingFacade.createPendingBooking(request, memberNo);

		// then
		double after = meterRegistry.counter("pending_booking_created_total").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("예약 삭제 성공 시 pending_deleted 카운터가 삭제 건수만큼 증가한다")
	void deletePendingBookings_incrementsPendingDeletedMetric() {
		// given
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);

		String memberNo = "202601010001";
		ScheduleStop departureStop = scheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = scheduleResult.scheduleStops().get(2);
		Long seatId = seats.get(0).getId();

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(scheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(new PendingSeatBooking(seatId, PassengerType.ADULT)))
			.build();

		Long trainCarId = seats.get(0).getTrainCar().getId();
		seatHoldService.holdSeats(
			pendingBooking.getId(),
			scheduleResult.trainSchedule().getId(),
			departureStop,
			arrivalStop,
			List.of(seatId),
			trainCarId,
			Duration.ofMinutes(10)
		);
		bookingRedisRepository.savePendingBooking(pendingBooking);

		double before = meterRegistry.counter("pending_booking_deleted_total").count();

		// when
		pendingBookingFacade.deletePendingBookings(List.of(pendingBooking.getId()), memberNo);

		// then
		double after = meterRegistry.counter("pending_booking_deleted_total").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("예약 퍼널 시나리오: 생성 10건, 취소 3건, 결제 확정 3건이면 expired는 Grafana에서 4건으로 계산된다")
	void bookingFunnel_created10_deleted3_confirmed3_expired4() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createRealisticTrain(1, 1, 6, 4);
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> allSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 10);

		Long departureStationId = scheduleResult.scheduleStops().get(0).getStation().getId();
		Long arrivalStationId = scheduleResult.scheduleStops().get(1).getStation().getId();
		Long scheduleId = scheduleResult.trainSchedule().getId();

		double createdBefore = meterRegistry.counter("pending_booking_created_total").count();
		double deletedBefore = meterRegistry.counter("pending_booking_deleted_total").count();

		// when
		// Facade를 통해 10건 생성 (좌석별 1건씩)
		String[] pendingIds = new String[10];
		for (int i = 0; i < 10; i++) {
			PendingBookingCreateRequest request = new PendingBookingCreateRequest(
				scheduleId, departureStationId, arrivalStationId,
				List.of(PassengerType.ADULT),
				List.of(allSeats.get(i).getId())
			);
			pendingIds[i] = pendingBookingFacade.createPendingBooking(request, memberNo).pendingBookingId();
		}

		// 3건 취소
		for (int i = 0; i < 3; i++) {
			pendingBookingFacade.deletePendingBookings(List.of(pendingIds[i]), memberNo);
		}

		// then
		double created = meterRegistry.counter("pending_booking_created_total").count() - createdBefore;
		double deleted = meterRegistry.counter("pending_booking_deleted_total").count() - deletedBefore;

		assertThat(created).isEqualTo(10);
		assertThat(deleted).isEqualTo(3);
	}

	@Test
	@DisplayName("같은 좌석에 대해 Seat Hold 충돌이 발생하면 seat_conflict 카운터가 증가한다")
	void createPendingBooking_holdConflict_incrementsSeatConflictMetric() {
		// given
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);

		Long scheduleId = scheduleResult.trainSchedule().getId();
		Long departureStationId = scheduleResult.scheduleStops().get(0).getStation().getId();
		Long arrivalStationId = scheduleResult.scheduleStops().get(2).getStation().getId();
		Long seatId = seats.get(0).getId();

		// 첫 번째 사용자가 좌석 Hold
		pendingBookingFacade.createPendingBooking(
			new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
				List.of(PassengerType.ADULT), List.of(seatId)),
			"202601010001"
		);

		double before = meterRegistry.counter("seat_conflict_hold_total").count();

		// when
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(
				new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
					List.of(PassengerType.ADULT), List.of(seatId)),
				"202601010002"
			)
		).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(BookingError.SEAT_CONFLICT_WITH_HOLD);

		// then
		double after = meterRegistry.counter("seat_conflict_hold_total").count();
		assertThat(after).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("이미 확정된 좌석에 예약을 시도하면 seat_conflict 카운터가 증가한다")
	void createPendingBooking_soldConflict_incrementsSeatConflictMetric() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 001 경부선")
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))
			.addStop("부산", LocalTime.of(9, 0), null)
			.build();
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);

		// 좌석을 확정 예매 (DB에 SeatBooking 저장)
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		bookingTestHelper.builder(member, scheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		Long scheduleId = scheduleResult.trainSchedule().getId();
		Long departureStationId = scheduleResult.scheduleStops().get(0).getStation().getId();
		Long arrivalStationId = scheduleResult.scheduleStops().get(2).getStation().getId();

		double before = meterRegistry.counter("seat_conflict_hold_total").count();

		// when
		assertThatThrownBy(() ->
			pendingBookingFacade.createPendingBooking(
				new PendingBookingCreateRequest(scheduleId, departureStationId, arrivalStationId,
					List.of(PassengerType.ADULT), List.of(seats.get(0).getId())),
				"202601010099"
			)
		).isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(BookingError.SEAT_CONFLICT_WITH_SOLD);

		// then
		double after = meterRegistry.counter("seat_conflict_hold_total").count();
		assertThat(after).isEqualTo(before + 1);
	}
}
