package com.sudo.raillo.payment.application.outbox;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.BookingSeatReleasePayload;
import com.sudo.raillo.payment.application.required.BookedSeatReleaser;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 삭제된 `Booking`의 Redis 좌석 점유({@code B:})를 해제한다.
 *
 * <p>`Booking` 행이 이미 없으므로 payload만으로 처리한다. 자기 {@code B:{bookingId}} 값일 때만 지우는
 * 비교 삭제라 다시 실행해도 안전하다.</p>
 *
 * <p>0건을 "좌석이 풀렸다"로 읽지 말 것. R에서 B로 전환되기 전에 지운 `Booking`은 {@code R:} field를
 * 남기고, 그 해제는 #270에서 한다. 경우 구분은 {@code SeatOccupancyRepository.releaseBooking} 참고.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingSeatReleaseProcessor implements OutboxEventProcessor {

	private final ObjectMapper objectMapper;
	private final BookedSeatReleaser bookedSeatReleaser;

	@Override
	public boolean supports(PaymentOutboxType type) {
		return type == PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED;
	}

	@Override
	public void process(String payload) {
		BookingSeatReleasePayload event = parse(payload);
		bookedSeatReleaser.release(event);
	}

	private BookingSeatReleasePayload parse(String payload) {
		BookingSeatReleasePayload event;
		try {
			event = objectMapper.readValue(payload, BookingSeatReleasePayload.class);
		} catch (JacksonException e) {
			log.error("[예매 점유 해제 - payload 역직렬화 실패] error={}", e.getMessage());
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		}
		if (event.schemaVersion() != BookingSeatReleasePayload.SCHEMA_VERSION
			|| event.seats() == null || event.seats().isEmpty()) {
			log.error("[예매 점유 해제 - 지원하지 않는 payload] schemaVersion={}, bookingId={}",
				event.schemaVersion(), event.bookingId());
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		}
		// 구간이 역전되면 Lua 순회가 돌지 않아 아무것도 지우지 않고 행이 DONE이 된다
		if (event.departureStopOrder() >= event.arrivalStopOrder()) {
			log.error("[예매 점유 해제 - 구간이 역전된 payload] bookingId={}, departure={}, arrival={}",
				event.bookingId(), event.departureStopOrder(), event.arrivalStopOrder());
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		}
		return event;
	}
}
