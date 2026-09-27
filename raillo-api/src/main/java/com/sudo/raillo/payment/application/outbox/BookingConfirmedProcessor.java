package com.sudo.raillo.payment.application.outbox;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.BookingConfirmedPayload;
import com.sudo.raillo.payment.application.required.BookedSeatWriter;
import com.sudo.raillo.payment.application.required.BookingReader;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.train.cache.TrainCacheKey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 승인 확정 후 Redis 좌석 점유를 예매 점유(R→B)로 바꾼다.
 *
 * <p>운행일이 지난 항목과 예매가 더는 유효하지 않은 항목은 좌석을 건드리지 않고 예약 본문과 회원 인덱스만 지운다.
 * 다른 예약이나 예매와 충돌한 항목이 있어도 나머지 항목은 모두 처리한 뒤 예외를 한 번만 던져 Outbox 재시도에 맡기지만,
 * 스크립트 오류(알 수 없는 좌석 값)는 데이터 오염이라 즉시 멈추고 알려야 하고 Redis 예외는 모든 항목에 똑같이 영향을 주므로
 * 두 경우는 나머지 항목을 처리하지 않고 그 항목에서 바로 멈춘다.
 * 이미 전환한 항목은 다시 처리해도 성공한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingConfirmedProcessor implements OutboxEventProcessor {

	private static final int SUPPORTED_SCHEMA_VERSION = 2;

	private final ObjectMapper objectMapper;
	private final BookingReader bookingReader;
	private final BookedSeatWriter bookedSeatWriter;

	@Override
	public boolean supports(PaymentOutboxType type) {
		return type == PaymentOutboxType.BOOKING_CONFIRMED;
	}

	@Override
	public void process(String payload) {
		BookingConfirmedPayload event = parse(payload);
		LocalDate today = LocalDate.now(TrainCacheKey.ZONE);
		boolean anyConflicted = false;
		for (BookingConfirmedPayload.Entry entry : event.bookings()) {
			if (!processEntry(event.paymentId(), entry, today)) {
				anyConflicted = true;
			}
		}
		if (anyConflicted) {
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_BOOKING_CONVERSION_CONFLICT);
		}
	}

	/** @return 이 항목이 충돌 없이 처리됐으면 true, 다른 예약이나 예매와 충돌했으면 false */
	private boolean processEntry(long paymentId, BookingConfirmedPayload.Entry entry, LocalDate today) {
		if (entry.operationDate().isBefore(today)) {
			log.info("[예매 점유 전환 생략 - 운행일 경과] paymentId={}, reservationId={}, operationDate={}",
				paymentId, entry.reservationId(), entry.operationDate());
			bookedSeatWriter.discardReservation(entry);
			return true;
		}
		if (!bookingReader.isBooked(entry.bookingId())) {
			log.warn("[예매 점유 전환 생략 - 유효하지 않은 예매] paymentId={}, reservationId={}, bookingId={}",
				paymentId, entry.reservationId(), entry.bookingId());
			bookedSeatWriter.discardReservation(entry);
			return true;
		}
		if (!bookedSeatWriter.markBooked(entry)) {
			log.warn("[예매 점유 전환 충돌 - 재시도 예정] paymentId={}, reservationId={}, bookingId={}",
				paymentId, entry.reservationId(), entry.bookingId());
			return false;
		}
		return true;
	}

	private BookingConfirmedPayload parse(String payload) {
		BookingConfirmedPayload event;
		try {
			event = objectMapper.readValue(payload, BookingConfirmedPayload.class);
		} catch (JacksonException e) {
			log.error("[예매 점유 전환 - payload 역직렬화 실패] error={}", e.getMessage());
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		}
		if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION || event.bookings() == null) {
			log.error("[예매 점유 전환 - 지원하지 않는 payload] schemaVersion={}", event.schemaVersion());
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);
		}
		return event;
	}
}
