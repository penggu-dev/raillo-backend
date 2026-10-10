package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.application.outbox.PaymentOutboxWorker;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingResult;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

/**
 * `Booking` 삭제부터 Redis 좌석 점유 해제까지 전 구간을 확인한다.
 *
 * <p>단계별 동작은 {@code BookingServiceTest}, {@code BookingSeatReleaseProcessorTest},
 * {@code SeatOccupancyRepositoryTest}가 각각 검증한다.</p>
 */
@ServiceTest
@DisplayName("예매 삭제 후 좌석 점유 해제 전 구간")
class BookingSeatReleaseFlowTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private PaymentOutboxWorker worker;

	@Autowired
	private PaymentOutboxRepository outboxRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancy;

	private Member member;
	private Booking booking;
	private Seat seat;
	private long scheduleId;
	private long carId;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.createDefault(train);
		seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);

		BookingResult result = bookingTestHelper.builder(member, scheduleResult)
			.addSeat(seat, PassengerType.ADULT)
			.build();
		booking = result.booking();
		scheduleId = scheduleResult.trainSchedule().getId();
		carId = seat.getTrainCar().getId();

		// 결제 확정 상태를 만든다
		List<SeatBooking> seatBookings = result.seatBookings();
		seatOccupancy.markBooked(scheduleId, carId, seat.getId(),
			seatBookings.get(0).getDepartureStopOrder(), seatBookings.get(0).getArrivalStopOrder(),
			String.valueOf(booking.getId()));
	}

	@Test
	@DisplayName("예매를 삭제하고 워커가 Outbox 행을 처리하면 예매 점유가 사라지고 행이 DONE이 된다")
	void deleteBooking_thenWorkerPoll_releasesSeatOccupancy() {
		// given
		assertThat(seatOccupancy.entries(scheduleId, carId)).isNotEmpty();
		bookingService.deleteBooking(member.getMemberDetail().getMemberNo(), booking.getId());

		// 삭제만으로는 점유가 그대로다
		assertThat(seatOccupancy.entries(scheduleId, carId)).isNotEmpty();

		// when
		worker.poll();

		// then
		assertThat(seatOccupancy.entries(scheduleId, carId)).isEmpty();

		PaymentOutbox outbox = outboxRepository
			.findByDeduplicationKey(
				PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED.deduplicationKey(booking.getId()))
			.orElseThrow();
		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
		assertThat(outbox.getProcessedAt()).isNotNull();
	}
}
