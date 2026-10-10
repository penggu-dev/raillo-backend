package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.BookingSeatReleasePayload;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.SeatOccupancyTestHelper;

import tools.jackson.databind.ObjectMapper;

@ServiceTest
@DisplayName("BookingSeatReleaseProcessor - 예매 점유 해제")
class BookingSeatReleaseProcessorTest {

	private static final long SCHEDULE_ID = 4001L;
	private static final long CAR_ID = 501L;
	private static final long SEAT_A = 11L;
	private static final long SEAT_B = 12L;
	private static final long BOOKING_ID = 77L;

	@Autowired
	private BookingSeatReleaseProcessor processor;

	@Autowired
	private OutboxEventDispatcher dispatcher;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SeatOccupancyTestHelper seatOccupancy;

	private String payload(long bookingId, int dep, int arr, long... seatIds) {
		List<BookingSeatReleasePayload.SeatEntry> seats = Arrays.stream(seatIds)
			.mapToObj(seatId -> new BookingSeatReleasePayload.SeatEntry(seatId, CAR_ID))
			.toList();
		return objectMapper.writeValueAsString(new BookingSeatReleasePayload(
			BookingSeatReleasePayload.SCHEMA_VERSION, bookingId, SCHEDULE_ID, dep, arr, seats));
	}

	@Test
	@DisplayName("예매 점유 해제 처리기가 Outbox 디스패처의 지원 타입으로 등록된다")
	void processor_is_registered_to_dispatcher() {
		// given - 스프링 컨텍스트

		// when
		List<PaymentOutboxType> supported = dispatcher.supportedTypes();

		// then
		assertThat(supported).contains(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED);
	}

	@Test
	@DisplayName("payload의 좌석 구간에서 자기 예매 점유가 사라진다")
	void process_releases_own_booking_fields() {
		// given
		seatOccupancy.markBooked(SCHEDULE_ID, CAR_ID, SEAT_A, 0, 2, String.valueOf(BOOKING_ID));

		// when
		processor.process(payload(BOOKING_ID, 0, 2, SEAT_A));

		// then
		assertThat(seatOccupancy.entries(SCHEDULE_ID, CAR_ID)).isEmpty();
	}

	@Test
	@DisplayName("다른 예매가 점유한 좌석은 그대로 남는다")
	void process_keeps_other_bookings_fields() {
		// given
		seatOccupancy.markBooked(SCHEDULE_ID, CAR_ID, SEAT_A, 0, 1, String.valueOf(BOOKING_ID));
		seatOccupancy.markBooked(SCHEDULE_ID, CAR_ID, SEAT_B, 0, 1, "88");

		// when
		processor.process(payload(BOOKING_ID, 0, 1, SEAT_A, SEAT_B));

		// then
		assertThat(seatOccupancy.valueOf(SCHEDULE_ID, CAR_ID, SEAT_A, 0)).isNull();
		assertThat(seatOccupancy.valueOf(SCHEDULE_ID, CAR_ID, SEAT_B, 0)).isEqualTo("B:88");
	}

	@Test
	@DisplayName("같은 payload를 다시 처리해도 성공한다")
	void process_is_idempotent() {
		// given
		seatOccupancy.markBooked(SCHEDULE_ID, CAR_ID, SEAT_A, 0, 2, String.valueOf(BOOKING_ID));
		String payload = payload(BOOKING_ID, 0, 2, SEAT_A);
		processor.process(payload);

		// when
		processor.process(payload);

		// then
		assertThat(seatOccupancy.entries(SCHEDULE_ID, CAR_ID)).isEmpty();
	}

	@Test
	@DisplayName("점유가 이미 없어도 성공한다")
	void process_succeeds_when_nothing_to_release() {
		// given - 아무 점유도 없다

		// when
		processor.process(payload(BOOKING_ID, 0, 2, SEAT_A));

		// then
		assertThat(seatOccupancy.entries(SCHEDULE_ID, CAR_ID)).isEmpty();
	}

	@Test
	@DisplayName("지원하지 않는 스키마 버전은 역직렬화 실패로 거부한다")
	void process_rejects_unsupported_schema_version() {
		// given
		String payload = objectMapper.writeValueAsString(new BookingSeatReleasePayload(
			BookingSeatReleasePayload.SCHEMA_VERSION + 1, BOOKING_ID, SCHEDULE_ID, 0, 2,
			List.of(new BookingSeatReleasePayload.SeatEntry(SEAT_A, CAR_ID))));

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
	}

	@Test
	@DisplayName("좌석 목록이 비어 있는 payload는 역직렬화 실패로 거부한다")
	void process_rejects_payload_without_seats() {
		// given
		String payload = objectMapper.writeValueAsString(new BookingSeatReleasePayload(
			BookingSeatReleasePayload.SCHEMA_VERSION, BOOKING_ID, SCHEDULE_ID, 0, 2, List.of()));

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
	}

	@Test
	@DisplayName("출발과 도착이 같은 payload는 역직렬화 실패로 거부한다")
	void process_rejects_empty_section_range() {
		// given - 구간이 0칸이면 Lua 순회가 돌지 않아 행이 조용히 DONE이 된다
		seatOccupancy.markBooked(SCHEDULE_ID, CAR_ID, SEAT_A, 0, 2, String.valueOf(BOOKING_ID));
		String payload = payload(BOOKING_ID, 2, 2, SEAT_A);

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		assertThat(seatOccupancy.entries(SCHEDULE_ID, CAR_ID)).isNotEmpty();
	}

	@Test
	@DisplayName("도착이 출발보다 앞선 payload는 역직렬화 실패로 거부한다")
	void process_rejects_reversed_section_range() {
		// given - 동일 구간만 테스트하면 역전 분기는 검증되지 않는다
		seatOccupancy.markBooked(SCHEDULE_ID, CAR_ID, SEAT_A, 0, 2, String.valueOf(BOOKING_ID));
		String payload = payload(BOOKING_ID, 2, 1, SEAT_A);

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		assertThat(seatOccupancy.entries(SCHEDULE_ID, CAR_ID)).isNotEmpty();
	}

	@Test
	@DisplayName("형식이 깨진 payload는 역직렬화 실패로 거부한다")
	void process_rejects_malformed_payload() {
		// given
		String payload = "{not-json";

		// when & then
		assertThatThrownBy(() -> processor.process(payload))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
	}
}
