package com.sudo.raillo.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.ScheduleStopTemplate;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.TrainScheduleTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BookingTest {

	@Test
	@DisplayName("예매 생성 시 상태가 BOOKED이고 예매 코드가 생성된다")
	void create() {
		// given
		Member member = createMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));
		Station departureStation = Station.create("서울");
		Station arrivalStation = Station.create("부산");
		ScheduleStopTemplate departureStopTemplate =
			ScheduleStopTemplate.create(0, null, LocalTime.of(9, 0), departureStation);
		ScheduleStopTemplate arrivalStopTemplate =
			ScheduleStopTemplate.create(1, LocalTime.of(12, 0), null, arrivalStation);
		TrainScheduleTemplate template = TrainScheduleTemplate.create(
			"KTX 001",
			0b1111111,
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			null,
			departureStation,
			arrivalStation,
			List.of(departureStopTemplate, arrivalStopTemplate)
		);
		TrainSchedule trainSchedule = TrainSchedule.create(LocalDate.now(), template);
		ScheduleStop departureStop = ScheduleStop.create(departureStopTemplate, trainSchedule);
		ScheduleStop arrivalStop = ScheduleStop.create(arrivalStopTemplate, trainSchedule);

		// when
		Booking booking = Booking.create(member, order, trainSchedule, departureStop, arrivalStop);

		// then
		assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.BOOKED);
		assertThat(booking.getBookingCode()).hasSize(18);
		assertThat(booking.getMember()).isEqualTo(member);
		assertThat(booking.getTrainSchedule()).isEqualTo(trainSchedule);
		assertThat(booking.getDepartureStop()).isEqualTo(departureStop);
		assertThat(booking.getArrivalStop()).isEqualTo(arrivalStop);
		assertThat(booking.getCancelledAt()).isNull();
	}

	@Test
	@DisplayName("예매 취소 시 상태가 CANCELLED로 변경되고 취소 시간이 설정된다")
	void cancel() {
		// given
		Member member = createMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));
		Booking booking = Booking.create(member, order, null, null, null);

		// when
		booking.cancel();

		// then
		assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(booking.getCancelledAt()).isNotNull();
	}

	@Test
	@DisplayName("이미 취소된 예매를 다시 취소하면 예외가 발생한다")
	void cancelFail() {
		// given
		Member member = createMember();
		Order order = Order.create(member, BigDecimal.valueOf(10000));
		Booking booking = Booking.create(member, order, null, null, null);
		booking.cancel();

		// when & then
		assertThatThrownBy(booking::cancel)
			.isInstanceOf(DomainException.class)
			.hasMessage(BookingError.BOOKING_ALREADY_CANCELLED.getMessage());
	}

	private Member createMember() {
		return Member.create(
			"member",
			"testPassword",
			"010-1111-1111",
			"202507300001",
			"test@example.com",
			LocalDate.of(2000, 1, 1),
			"M"
		);
	}
}
