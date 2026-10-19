package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.application.BookingConfirmedPayload;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.application.result.ConfirmedBookingResult;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper.SeatPassenger;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

import tools.jackson.databind.ObjectMapper;

/**
 * Outbox Worker가 실제 Reservation 처리기와 adapter, Redis를 통과하는 경로를 검증한다.
 *
 * <p>{@link PaymentOutboxWorkerIntegrationTest}는 dispatcher를 가짜로 두고 재시도와 선점 규칙만 본다.
 * 여기서는 가짜를 쓰지 않고 Outbox 행 하나가 Redis 좌석 점유까지 바꾸는지를 본다.</p>
 */
@ServiceTest
@DisplayName("PaymentOutboxWorker - 실제 Reservation 처리 경로")
class PaymentOutboxWorkerReservationIntegrationTest {

	private static final long OTHER_PAYMENT_ID = 9_000L;

	@Autowired
	private PaymentOutboxWorker worker;

	@Autowired
	private PaymentOutboxRepository outboxRepository;

	@Autowired
	private OutboxProperties properties;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private PaymentReservationTestHelper paymentReservations;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancy;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private BookingRepository bookingRepository;

	private Member member;
	private TrainScheduleResult scheduleResult;
	private List<Seat> seats;
	private Reservation reservation;
	private Booking booking;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		scheduleResult = trainScheduleTestHelper.createDefault(train);
		seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		reservation = reserve(seats.get(0));
		booking = bookingTestHelper.createDefault(member, scheduleResult).booking();
	}

	@Test
	@DisplayName("PENDING 행 하나를 폴링하면 좌석이 예매 점유로 바뀌고 행이 DONE이 된다")
	void poll_convertsReservationToBooking_andMarksDone() {
		// given
		PaymentOutbox row = saveOutbox(1L, List.of(reservation), booking.getId());

		// when
		worker.poll();

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(reservationBodyExists(reservation)).isFalse();
		assertThat(statusOf(row)).isEqualTo(PaymentOutboxStatus.DONE);
	}

	@Test
	@DisplayName("Redis 전환이 끝났는데 DONE이 커밋되지 않아 같은 payload를 다시 폴링해도 좌석과 상태가 유지된다")
	void poll_replayAfterUncommittedDone_isSafe() {
		// given 전환까지 끝낸 뒤, DONE 커밋이 유실된 상황을 같은 payload의 새 PENDING 행으로 모델링한다
		saveOutbox(1L, List.of(reservation), booking.getId());
		worker.poll();
		PaymentOutbox replay = saveOutbox(2L, List.of(reservation), booking.getId());

		// when
		worker.poll();

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(statusOf(replay)).isEqualTo(PaymentOutboxStatus.DONE);
	}

	@Test
	@DisplayName("두 예약 중 하나가 다른 예약과 충돌하면 나머지는 전환되고 행은 재시도로 남는다")
	void poll_partialConflict_convertsRestAndSchedulesRetry() {
		// given 두 번째 예약의 좌석을 다른 예약이 가져간 상태
		Reservation second = reserve(seats.get(1));
		markReservedByOther(second, seats.get(1));
		PaymentOutbox row = saveOutbox(1L, List.of(reservation, second), booking.getId());

		// when
		worker.poll();

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(seatValue(second, seats.get(1))).isEqualTo("R:RV-OTHER");
		PaymentOutbox reloaded = outboxRepository.findById(row.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(reloaded.getRetryCount()).isEqualTo(1);
		assertThat(reloaded.getNextRetryAt()).isNotNull();
	}

	@Test
	@DisplayName("충돌이 풀린 뒤 백오프가 지나 다시 폴링하면 남은 예약도 전환되고 행이 DONE이 된다")
	void poll_resumesAfterConflictCleared() throws InterruptedException {
		// given 충돌로 한 번 실패시킨 뒤 충돌을 없앤다
		Reservation second = reserve(seats.get(1));
		markReservedByOther(second, seats.get(1));
		PaymentOutbox row = saveOutbox(1L, List.of(reservation, second), booking.getId());
		worker.poll();
		deleteSeatField(second, seats.get(1));
		Thread.sleep(properties.initialBackoff().toMillis() + 200);

		// when
		worker.poll();

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(seatValue(second, seats.get(1))).isEqualTo("B:" + booking.getId());
		assertThat(statusOf(row)).isEqualTo(PaymentOutboxStatus.DONE);
	}

	@Test
	@DisplayName("예매가 유효하지 않은 행을 폴링할 때 그 좌석을 새로 가져간 예약의 점유는 남는다")
	void poll_releasesStaleEntry_keepingNewReservation() {
		// given 예매가 사라진 사이 다른 예약이 같은 좌석을 가져갔다
		markReservedByOther(reservation, seats.get(0));
		PaymentOutbox row = saveOutbox(1L, List.of(reservation), Long.MAX_VALUE);

		// when
		worker.poll();

		// then 해제는 자기 R: field만 지우므로 새 예약의 점유가 남는다
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("R:RV-OTHER");
		assertThat(reservationBodyExists(reservation)).isFalse();
		assertThat(statusOf(row)).isEqualTo(PaymentOutboxStatus.DONE);
	}

	@Test
	@DisplayName("예약 TTL이 만료돼 본문과 좌석 field가 사라진 뒤 폴링해도 예매 점유를 쓰고 행이 DONE이 된다")
	void poll_afterReservationExpired_writesBookingOccupancy() {
		// given TTL 만료를 본문과 좌석 field 삭제로 모델링한다
		PaymentOutbox row = saveOutbox(1L, List.of(reservation), booking.getId());
		deleteSeatField(reservation, seats.get(0));
		stringRedisTemplate.delete(
			ReservationCacheKey.reservation(reservation.trainScheduleId(), reservation.reservationId()));

		// when
		worker.poll();

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(statusOf(row)).isEqualTo(PaymentOutboxStatus.DONE);
	}

	@Test
	@DisplayName("읽을 수 없는 payload 행이 같은 배치의 정상 행 처리를 막지 않는다")
	void poll_brokenPayload_doesNotBlockHealthyRow() {
		// given
		PaymentOutbox broken = outboxRepository.save(
			PaymentOutbox.forBookingConfirmed(OTHER_PAYMENT_ID, "{not json"));
		PaymentOutbox healthy = saveOutbox(1L, List.of(reservation), booking.getId());

		// when
		worker.poll();

		// then 정상 행은 끝까지 처리되고, 깨진 행만 재시도로 남는다
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(statusOf(healthy)).isEqualTo(PaymentOutboxStatus.DONE);
		PaymentOutbox reloadedBroken = outboxRepository.findById(broken.getId()).orElseThrow();
		assertThat(reloadedBroken.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(reloadedBroken.getRetryCount()).isEqualTo(1);
	}

	private Reservation reserve(Seat seat) {
		Reservation created = paymentReservations.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(scheduleResult.trainSchedule().getId())
			.withDepartureStopId(scheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(scheduleResult.scheduleStops().get(1).getId())
			.withSeats(List.of(new SeatPassenger(seat.getId(), PassengerType.ADULT)))
			.build();
		paymentReservations.save(created);
		return created;
	}

	private PaymentOutbox saveOutbox(long paymentId, List<Reservation> reservations, long bookingId) {
		PaymentAttempt attempt = PaymentAttempt.startApproval(paymentId, "attempt-" + paymentId, "pk-" + paymentId);
		List<ConfirmedBookingResult> confirmed = reservations.stream()
			.map(r -> new ConfirmedBookingResult(r.reservationId(), bookingId))
			.toList();
		String payload = objectMapper.writeValueAsString(
			BookingConfirmedPayload.from(paymentId, attempt, reservations, confirmed));
		return outboxRepository.save(PaymentOutbox.forBookingConfirmed(paymentId, payload));
	}

	private PaymentOutboxStatus statusOf(PaymentOutbox row) {
		return outboxRepository.findById(row.getId()).orElseThrow().getStatus();
	}

	private void markReservedByOther(Reservation r, Seat seat) {
		seatOccupancy.markReserved(r.trainScheduleId(), seat.getTrainCar().getId(), seat.getId(),
			r.departure().stopOrder(), r.arrival().stopOrder(), "RV-OTHER");
	}

	private void deleteSeatField(Reservation r, Seat seat) {
		stringRedisTemplate.opsForHash().delete(
			ReservationCacheKey.carSeats(r.trainScheduleId(), seat.getTrainCar().getId()),
			ReservationCacheKey.seatField(seat.getId(), r.departure().stopOrder()));
	}

	private String seatValue(Reservation r, Seat seat) {
		return seatOccupancy.valueOf(r.trainScheduleId(), seat.getTrainCar().getId(), seat.getId(),
			r.departure().stopOrder());
	}

	private boolean reservationBodyExists(Reservation r) {
		return Boolean.TRUE.equals(
			stringRedisTemplate.hasKey(ReservationCacheKey.reservation(r.trainScheduleId(), r.reservationId())));
	}
}
