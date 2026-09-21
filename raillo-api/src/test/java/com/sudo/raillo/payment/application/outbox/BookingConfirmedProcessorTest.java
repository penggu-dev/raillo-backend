package com.sudo.raillo.payment.application.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.application.BookingConfirmedPayload;
import com.sudo.raillo.payment.application.required.PendingBookingDeleter;
import com.sudo.raillo.payment.application.required.SeatHoldReleaser;
import com.sudo.raillo.payment.application.required.TrainScheduleReader;
import com.sudo.raillo.payment.application.required.TrainSeatReader;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class BookingConfirmedProcessorTest {

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	@Mock
	private PendingBookingDeleter pendingBookingDeleter;

	@Mock
	private SeatHoldReleaser seatHoldReleaser;

	@Mock
	private TrainScheduleReader trainScheduleReader;

	@Mock
	private TrainSeatReader trainSeatReader;

	private BookingConfirmedProcessor sut;

	@BeforeEach
	void setUp() {
		sut = new BookingConfirmedProcessor(
			objectMapper,
			pendingBookingDeleter,
			seatHoldReleaser,
			trainScheduleReader,
			trainSeatReader
		);
	}

	@Test
	@DisplayName("BOOKING_CONFIRMED 타입을 지원한다")
	void supports_bookingConfirmed() {
		assertThat(sut.supports(PaymentOutboxType.BOOKING_CONFIRMED)).isTrue();
	}

	@Test
	@DisplayName("payload를 역직렬화해 PendingBooking 삭제와 Seat Hold 해제를 순차 호출한다")
	void process_deletesPendingBookings_andReleasesSeatHolds() throws Exception {
		// given
		BookingConfirmedPayload payload = new BookingConfirmedPayload(List.of(
			new BookingConfirmedPayload.Entry("pb-1", "MEM123", 100L, 10L, 20L, List.of(1001L, 1002L))
		));
		String json = objectMapper.writeValueAsString(payload);

		ScheduleStop depart = mockStop(10L, 1);
		ScheduleStop arrive = mockStop(20L, 5);
		when(trainScheduleReader.getScheduleStops(anyList())).thenReturn(List.of(depart, arrive));
		when(trainSeatReader.getTrainCarId(List.of(1001L, 1002L))).thenReturn(500L);

		// when
		sut.process(json);

		// then
		verify(pendingBookingDeleter).deletePendingBookings(List.of("pb-1"), "MEM123");
		verify(seatHoldReleaser).releaseSeats("pb-1", 100L, List.of(1001L, 1002L), 500L, 1, 5);
	}

	@Test
	@DisplayName("payload의 pendingBookings가 비어 있으면 아무 것도 하지 않는다")
	void process_noOp_whenPayloadEmpty() throws Exception {
		String json = objectMapper.writeValueAsString(new BookingConfirmedPayload(List.of()));

		sut.process(json);

		verifyNoInteractions(pendingBookingDeleter, seatHoldReleaser, trainScheduleReader, trainSeatReader);
	}

	@Test
	@DisplayName("malformed JSON이면 PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED 예외를 던진다")
	void process_throws_whenPayloadIsMalformed() {
		assertThatThrownBy(() -> sut.process("not-a-json"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_OUTBOX_PAYLOAD_DESERIALIZATION_FAILED);

		verify(pendingBookingDeleter, never()).deletePendingBookings(any(), any());
		verify(seatHoldReleaser, never()).releaseSeats(any(), any(), any(), any(), anyInt(), anyInt());
	}

	private ScheduleStop mockStop(Long id, int stopOrder) {
		ScheduleStop stop = mock(ScheduleStop.class);
		when(stop.getId()).thenReturn(id);
		when(stop.getStopOrder()).thenReturn(stopOrder);
		return stop;
	}
}
