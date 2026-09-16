package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.BookingConfirmedPayload;
import com.sudo.raillo.payment.application.required.PendingBookingReader;
import com.sudo.raillo.payment.application.required.SeatHoldReleaser;
import com.sudo.raillo.payment.application.required.TrainScheduleReader;
import com.sudo.raillo.payment.application.required.TrainSeatReader;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingConfirmedProcessor implements OutboxEventProcessor {

	private final ObjectMapper objectMapper;
	private final PendingBookingReader pendingBookingReader;
	private final SeatHoldReleaser seatHoldReleaser;
	private final TrainScheduleReader trainScheduleReader;
	private final TrainSeatReader trainSeatReader;

	@Override
	public boolean supports(PaymentOutboxType type) {
		return type == PaymentOutboxType.BOOKING_CONFIRMED;
	}

	@Override
	public void process(String payload) {
		BookingConfirmedPayload deserialized = deserialize(payload);
		List<BookingConfirmedPayload.Entry> entries = deserialized.pendingBookings();
		if (entries.isEmpty()) {
			return;
		}

		deletePendingBookings(entries);
		releaseAllSeatHolds(entries);

		log.info("[Outbox 처리 완료: BOOKING_CONFIRMED] pendingBookingCount={}", entries.size());
	}

	private BookingConfirmedPayload deserialize(String payload) {
		try {
			return objectMapper.readValue(payload, BookingConfirmedPayload.class);
		} catch (JacksonException e) {
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED, e);
		}
	}

	private void deletePendingBookings(List<BookingConfirmedPayload.Entry> entries) {
		List<String> pendingBookingIds = entries.stream()
			.map(BookingConfirmedPayload.Entry::pendingBookingId)
			.toList();
		String memberNo = entries.get(0).memberNo();
		pendingBookingReader.deletePendingBookings(pendingBookingIds, memberNo);
	}

	private void releaseAllSeatHolds(List<BookingConfirmedPayload.Entry> entries) {
		List<Long> allStopIds = entries.stream()
			.flatMap(e -> Stream.of(e.departureStopId(), e.arrivalStopId()))
			.toList();

		Map<Long, ScheduleStop> stopMap = trainScheduleReader.getScheduleStops(allStopIds).stream()
			.collect(Collectors.toMap(ScheduleStop::getId, Function.identity()));

		entries.forEach(entry -> releaseSingle(entry, stopMap));
	}

	private void releaseSingle(BookingConfirmedPayload.Entry entry, Map<Long, ScheduleStop> stopMap) {
		List<Long> seatIds = entry.seatIds();
		Long trainCarId = trainSeatReader.getTrainCarId(seatIds);
		ScheduleStop departureStop = stopMap.get(entry.departureStopId());
		ScheduleStop arrivalStop = stopMap.get(entry.arrivalStopId());

		seatHoldReleaser.releaseSeats(
			entry.pendingBookingId(),
			entry.trainScheduleId(),
			seatIds,
			trainCarId,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);
	}
}
