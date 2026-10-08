package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.application.BookingConfirmedPayload;
import com.sudo.raillo.payment.application.result.ConfirmedBookingResult;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper;
import com.sudo.raillo.support.helper.PaymentReservationTestHelper.SeatPassenger;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.cache.TrainCacheKey;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

import tools.jackson.databind.ObjectMapper;

@ServiceTest
@DisplayName("BookingConfirmedProcessor - 예매 점유 전환")
class BookingConfirmedProcessorTest {

	@Autowired
	private BookingConfirmedProcessor processor;

	@Autowired
	private OutboxEventDispatcher dispatcher;

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
	@DisplayName("예매 확정 처리기가 Outbox 디스패처의 지원 타입으로 등록된다")
	void processor_is_registered_to_dispatcher() {
		// given - 스프링 컨텍스트

		// when
		List<PaymentOutboxType> supported = dispatcher.supportedTypes();

		// then
		assertThat(supported).contains(PaymentOutboxType.BOOKING_CONFIRMED);
	}

	@Test
	@DisplayName("자기 예약 점유를 예매 점유로 바꾸고 예약 본문과 회원 인덱스를 지운다")
	void process_converts_reservation_to_booking() {
		// given
		String payload = payload(List.of(reservation), booking.getId());

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(reservationBodyExists(reservation)).isFalse();
		assertThat(memberIndexExists(reservation)).isFalse();
	}

	@Test
	@DisplayName("같은 payload를 다시 처리해도 성공하고 예매 점유를 유지한다")
	void process_is_idempotent() {
		// given
		String payload = payload(List.of(reservation), booking.getId());
		processor.process(payload);

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
	}

	@Test
	@DisplayName("운행일이 오늘인 항목은 지난 운행일로 보지 않고 예매 점유로 바꾼다")
	void process_converts_entry_operating_today() {
		// given
		String payload = payload(List.of(withOperationDate(reservation, LocalDate.now(TrainCacheKey.ZONE))), booking.getId());

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
	}

	@Test
	@DisplayName("운행일이 지난 항목은 좌석을 바꾸지 않고 예약 본문과 회원 인덱스만 지운다")
	void process_skips_seats_for_past_operation_date() {
		// given
		String payload = payload(List.of(withOperationDate(reservation, LocalDate.now(TrainCacheKey.ZONE).minusDays(1))), booking.getId());

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("R:" + reservation.reservationId());
		assertThat(reservationBodyExists(reservation)).isFalse();
		assertThat(memberIndexExists(reservation)).isFalse();
	}

	@Test
	@DisplayName("예매가 취소됐으면 좌석을 바꾸지 않고 예약 본문과 회원 인덱스만 지운다")
	void process_skips_seats_for_cancelled_booking() {
		// given
		Booking cancelled = bookingRepository.findById(booking.getId()).orElseThrow();
		cancelled.cancel();
		bookingRepository.save(cancelled);
		String payload = payload(List.of(reservation), booking.getId());

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("R:" + reservation.reservationId());
		assertThat(reservationBodyExists(reservation)).isFalse();
		assertThat(memberIndexExists(reservation)).isFalse();
	}

	@Test
	@DisplayName("예매가 존재하지 않으면 좌석을 바꾸지 않고 예약 본문과 회원 인덱스만 지운다")
	void process_skips_seats_for_missing_booking() {
		// given
		String payload = payload(List.of(reservation), Long.MAX_VALUE);

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("R:" + reservation.reservationId());
		assertThat(reservationBodyExists(reservation)).isFalse();
		assertThat(memberIndexExists(reservation)).isFalse();
	}

	@Test
	@DisplayName("좌석 점유가 비어 있어도 예매가 유효하면 예매 점유를 쓴다")
	void process_writes_booking_when_seat_field_is_empty() {
		// given - 예약 field가 만료된 상황
		stringRedisTemplate.opsForHash().delete(
			ReservationCacheKey.carSeats(reservation.trainScheduleId(), seats.get(0).getTrainCar().getId()),
			ReservationCacheKey.seatField(seats.get(0).getId(), reservation.departure().stopOrder()));
		String payload = payload(List.of(reservation), booking.getId());

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
	}

	@Test
	@DisplayName("다른 예약이 좌석을 점유하고 있으면 PAYMENT_OUTBOX_BOOKING_CONVERSION_CONFLICT 예외를 던지고 좌석을 바꾸지 않는다")
	void process_throws_on_conflict() {
		// given
		markReservedByOther(reservation, seats.get(0));
		String payload = payload(List.of(reservation), booking.getId());

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_BOOKING_CONVERSION_CONFLICT.getMessage());
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("R:RV-OTHER");
		assertThat(reservationBodyExists(reservation)).isTrue();
		assertThat(memberIndexExists(reservation)).isTrue();
	}

	@Test
	@DisplayName("좌석 값이 오염돼 있으면 SEAT_OCCUPANCY_CORRUPTED 예외가 처리기 밖으로 그대로 나온다")
	void process_propagates_corruption_unwrapped() {
		// given - 좌석 점유 값이 아닌 값을 직접 심는다
		stringRedisTemplate.opsForHash().put(
			ReservationCacheKey.carSeats(reservation.trainScheduleId(),
				seats.get(0).getTrainCar().getId()),
			ReservationCacheKey.seatField(seats.get(0).getId(), reservation.departure().stopOrder()),
			"Z:corrupted");
		String payload = payload(List.of(reservation), booking.getId());

		// when & then 워커가 재시도 불가로 갈라낼 수 있으려면 이 타입이 감싸이지 않고 올라와야 한다
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.SEAT_OCCUPANCY_CORRUPTED);
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("Z:corrupted");
		assertThat(reservationBodyExists(reservation)).isTrue();
	}

	@Test
	@DisplayName("앞 항목만 충돌해도 뒤 항목까지 처리한 뒤 예외를 던진다")
	void process_processes_remaining_entries_before_throwing_on_conflict() {
		// given
		Reservation second = reserve(seats.get(1));
		markReservedByOther(reservation, seats.get(0));
		String payload = payload(List.of(reservation, second), booking.getId());

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_BOOKING_CONVERSION_CONFLICT.getMessage());
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("R:RV-OTHER");
		assertThat(seatValue(second, seats.get(1))).isEqualTo("B:" + booking.getId());
	}

	@Test
	@DisplayName("뒤 항목만 충돌해 실패한 payload는 충돌이 풀린 뒤 다시 처리하면 모든 항목이 예매 점유가 된다")
	void process_retries_after_partial_conflict() {
		// given
		Reservation second = reserve(seats.get(1));
		markReservedByOther(second, seats.get(1));
		String payload = payload(List.of(reservation, second), booking.getId());
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_BOOKING_CONVERSION_CONFLICT.getMessage());
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());

		stringRedisTemplate.opsForHash().delete(
			ReservationCacheKey.carSeats(second.trainScheduleId(), seats.get(1).getTrainCar().getId()),
			ReservationCacheKey.seatField(seats.get(1).getId(), second.departure().stopOrder()));

		// when
		processor.process(payload);

		// then
		assertThat(seatValue(reservation, seats.get(0))).isEqualTo("B:" + booking.getId());
		assertThat(seatValue(second, seats.get(1))).isEqualTo("B:" + booking.getId());
	}

	@Test
	@DisplayName("JSON이 손상됐거나 스키마 버전이 2가 아니거나 bookings가 없는 payload는 PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED 예외가 발생한다")
	void process_rejects_invalid_payload() {
		// given
		String broken = "{not json";
		String oldVersion = "{\"schemaVersion\":1,\"paymentId\":1,\"attemptId\":\"a\",\"bookings\":[]}";
		String missingBookings = "{\"schemaVersion\":2,\"paymentId\":1,\"attemptId\":\"a\"}";

		// when & then
		assertThatThrownBy(() -> processor.process(broken))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED.getMessage());
		assertThatThrownBy(() -> processor.process(oldVersion))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED.getMessage());
		// schemaVersion은 2이지만 bookings 필드 자체가 없는 payload도 같은 예외로 거절한다
		assertThatThrownBy(() -> processor.process(missingBookings))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED.getMessage());
	}

	@Test
	@DisplayName("#257 형식(schemaVersion 없음) payload는 PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED 예외가 발생한다")
	void process_rejects_legacy_257_payload() {
		// given
		String legacyPayload = "{\"pendingBookings\":[{\"pendingBookingId\":\"PB1\",\"memberNo\":\"M1\","
			+ "\"trainScheduleId\":1,\"departureStopId\":1,\"arrivalStopId\":2,\"seatIds\":[1]}]}";

		// when & then
		assertThatThrownBy(() -> processor.process(legacyPayload))
			.isInstanceOf(BusinessException.class)
			.hasMessage(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED.getMessage());
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

	private String payload(List<Reservation> reservations, long bookingId) {
		PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-test", "pk-test");
		List<ConfirmedBookingResult> confirmed = reservations.stream()
			.map(r -> new ConfirmedBookingResult(r.reservationId(), bookingId))
			.toList();
		return objectMapper.writeValueAsString(BookingConfirmedPayload.from(1L, attempt, reservations, confirmed));
	}

	private static Reservation withOperationDate(Reservation r, LocalDate operationDate) {
		return new Reservation(r.reservationId(), r.memberNo(), r.trainScheduleId(), r.trainNumber(), r.trainName(),
			operationDate, r.departure(), r.arrival(), r.departureAt(), r.carType(), r.seats(), r.totalFare(),
			r.createdAt(), r.expiresAt());
	}

	private void markReservedByOther(Reservation r, Seat seat) {
		seatOccupancy.markReserved(r.trainScheduleId(), seat.getTrainCar().getId(), seat.getId(),
			r.departure().stopOrder(), r.arrival().stopOrder(), "RV-OTHER");
	}

	private String seatValue(Reservation r, Seat seat) {
		return seatOccupancy.valueOf(r.trainScheduleId(), seat.getTrainCar().getId(), seat.getId(),
			r.departure().stopOrder());
	}

	private boolean reservationBodyExists(Reservation r) {
		return Boolean.TRUE.equals(
			stringRedisTemplate.hasKey(ReservationCacheKey.reservation(r.trainScheduleId(), r.reservationId())));
	}

	private boolean memberIndexExists(Reservation r) {
		return stringRedisTemplate.opsForHash()
			.hasKey(ReservationCacheKey.memberReservations(r.memberNo()), r.reservationId());
	}
}
