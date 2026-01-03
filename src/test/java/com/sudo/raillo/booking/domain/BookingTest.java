package com.sudo.raillo.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.support.fixture.BookingFixture;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.train.ScheduleStopFixture;
import com.sudo.raillo.support.fixture.train.StationFixture;
import com.sudo.raillo.support.fixture.train.TrainScheduleFixture;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BookingTest {

	@Test
	@DisplayName("예약 생성 시 상태가 BOOKED이고 예매 코드가 생성된다")
	void create() {
		// given
		Member member = MemberFixture.create();
		Station departureStation = StationFixture.create("서울");
		Station arrivalStation = StationFixture.create("부산");

		TrainSchedule trainSchedule = TrainScheduleFixture.create(
			"KTX 001",
			LocalDate.now(),
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			OperationStatus.ACTIVE,
			null,
			departureStation,
			arrivalStation
		);

		ScheduleStop departureStop = ScheduleStopFixture.create(
			0,
			null,
			LocalTime.of(9, 0),
			trainSchedule,
			departureStation
		);

		ScheduleStop arrivalStop = ScheduleStopFixture.create(
			1,
			LocalTime.of(12, 0),
			null,
			trainSchedule,
			arrivalStation
		);

		// when
		Booking booking = Booking.create(member, trainSchedule, departureStop, arrivalStop);

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
	@DisplayName("예약 취소 시 상태가 CANCELLED로 변경되고 취소 시간이 설정된다")
	void cancel() {
		// given
		Member member = MemberFixture.create();
		Booking booking = BookingFixture.create(member);

		// when
		booking.cancel();

		// then
		assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(booking.getCancelledAt()).isNotNull();
	}

	@Test
	@DisplayName("이미 취소된 예약을 다시 취소하면 예외가 발생한다")
	void cancelFail() {
		// given
		Member member = MemberFixture.create();
		Booking booking = BookingFixture.create(member);
		booking.cancel();

		// when & then
		assertThatThrownBy(booking::cancel)
			.isInstanceOf(DomainException.class)
			.hasMessage(BookingError.BOOKING_ALREADY_CANCELLED.getMessage());
	}
}
