package com.sudo.raillo.booking.application.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.exception.BusinessException;
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
import com.sudo.raillo.train.domain.type.CarType;

import io.micrometer.core.instrument.MeterRegistry;

@ServiceTest
@DisplayName("BookingMetrics - 예약 생성 메트릭")
class ReservationMetricsTest {

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
	private MeterRegistry meterRegistry;

	private String memberNo;
	private Long scheduleId;
	private Seat seat;
	private ReservationCreateRequest request;

	@BeforeEach
	void setUp() {
		Member member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.builder()
			.train(train)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("부산", LocalTime.of(8, 0), null)
			.build();
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 30000, 50000);
		trainCacheTestHelper.seed(train, scheduleResult);

		scheduleId = scheduleResult.trainSchedule().getId();
		seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
		request = new ReservationCreateRequest(scheduleId,
			scheduleResult.scheduleStops().get(0).getStation().getId(),
			scheduleResult.scheduleStops().get(1).getStation().getId(),
			List.of(PassengerType.ADULT), List.of(seat.getId()));
	}

	private double created() {
		return meterRegistry.get("pending_booking_created_total").counter().count();
	}

	private double conflicts(String type) {
		return meterRegistry.get("seat_conflict_total").tag("conflict_type", type).counter().count();
	}

	private long timerCount(String name) {
		return meterRegistry.get(name).timer().count();
	}

	@Test
	@DisplayName("예약 생성에 성공하면 생성 카운터와 두 타이머가 증가한다")
	void success_increments_created_and_timers() {
		// given
		double createdBefore = created();
		long facadeTimerBefore = timerCount("pending_booking_duration_seconds");
		long holdTimerBefore = timerCount("seat_hold_duration_seconds");

		// when
		reservationFacade.createReservation(request, memberNo);

		// then
		assertThat(created()).isEqualTo(createdBefore + 1);
		assertThat(timerCount("pending_booking_duration_seconds")).isEqualTo(facadeTimerBefore + 1);
		assertThat(timerCount("seat_hold_duration_seconds")).isEqualTo(holdTimerBefore + 1);
	}

	@Test
	@DisplayName("Hold 충돌이면 hold 충돌 카운터만 증가하고 생성 카운터는 그대로다")
	void hold_conflict_increments_hold_counter() {
		// given
		seatOccupancyTestHelper.markHeld(scheduleId, seat.getTrainCar().getId(), seat.getId(), 0, 1, "OTHER");
		double createdBefore = created();
		double holdBefore = conflicts("hold");
		double soldBefore = conflicts("sold");

		// when
		assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo)).isInstanceOf(BusinessException.class);

		// then
		assertThat(conflicts("hold")).isEqualTo(holdBefore + 1);
		assertThat(conflicts("sold")).isEqualTo(soldBefore);
		assertThat(created()).isEqualTo(createdBefore);
	}

	@Test
	@DisplayName("판매 충돌이면 sold 충돌 카운터만 증가하고 타이머는 기록된다")
	void sold_conflict_increments_sold_counter() {
		// given
		seatOccupancyTestHelper.markSold(scheduleId, seat.getTrainCar().getId(), seat.getId(), 0, 1, "77");
		double holdBefore = conflicts("hold");
		double soldBefore = conflicts("sold");
		long holdTimerBefore = timerCount("seat_hold_duration_seconds");

		// when
		assertThatThrownBy(() -> reservationFacade.createReservation(request, memberNo)).isInstanceOf(BusinessException.class);

		// then
		assertThat(conflicts("sold")).isEqualTo(soldBefore + 1);
		assertThat(conflicts("hold")).isEqualTo(holdBefore);
		assertThat(timerCount("seat_hold_duration_seconds")).isEqualTo(holdTimerBefore + 1);
	}
}
