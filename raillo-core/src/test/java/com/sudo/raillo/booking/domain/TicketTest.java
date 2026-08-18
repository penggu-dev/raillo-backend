package com.sudo.raillo.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.support.fixture.BookingFixture;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;
import com.sudo.raillo.support.fixture.TicketFixture;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketTest {

	private Member member;
	private Order order;
	private Booking booking;

	@BeforeEach
	void setUp() {
		member = MemberFixture.create();
		order = OrderFixture.create(member);
		booking = BookingFixture.create(member, order);
	}

	@Test
	@DisplayName("티켓 생성 시 상태가 ISSUED이고 정보가 올바르게 설정된다")
	void create_success() {
		// given
		BigDecimal fare = BigDecimal.valueOf(50000);
		PassengerType passengerType = PassengerType.ADULT;

		// when
		Ticket ticket = TicketFixture.builder()
			.withBooking(booking)
			.withPassengerType(passengerType)
			.withFare(fare)
			.build();

		// then
		assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
		assertThat(ticket.getBooking()).isEqualTo(booking);
		assertThat(ticket.getPassengerType()).isEqualTo(passengerType);
		assertThat(ticket.getFare()).isEqualTo(fare);
	}

	@Test
	@DisplayName("티켓 취소 시 상태가 CANCELLED로 변경된다")
	void cancel_success() {
		// given
		Ticket ticket = TicketFixture.create(booking);

		// when
		ticket.cancel();

		// then
		assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.CANCELLED);
	}

	@Test
	@DisplayName("이미 취소된 티켓을 다시 취소하면 예외가 발생한다")
	void cancel_whenAlreadyCancelled_throwsException() {
		// given
		Ticket ticket = TicketFixture.create(booking);
		ticket.cancel();

		// when & then
		assertThatThrownBy(ticket::cancel)
			.isInstanceOf(DomainException.class)
			.hasMessage(BookingError.TICKET_NOT_CANCELLABLE.getMessage());
	}

	@Test
	@DisplayName("이미 사용된 티켓을 취소하면 예외가 발생한다")
	void cancel_whenAlreadyUsed_throwsException() {
		// given
		Ticket ticket = TicketFixture.create(booking);
		ticket.use();

		// when & then
		assertThatThrownBy(ticket::cancel)
			.isInstanceOf(DomainException.class)
			.hasMessage(BookingError.TICKET_NOT_CANCELLABLE.getMessage());
	}

	@Test
	@DisplayName("티켓 사용 시 상태가 USED로 변경된다")
	void use_success() {
		// given
		Ticket ticket = TicketFixture.create(booking);

		// when
		ticket.use();

		// then
		assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.USED);
	}

	@Test
	@DisplayName("이미 사용된 티켓을 다시 사용하면 예외가 발생한다")
	void use_whenAlreadyUsed_throwsException() {
		// given
		Ticket ticket = TicketFixture.create(booking);
		ticket.use();

		// when & then
		assertThatThrownBy(ticket::use)
			.isInstanceOf(DomainException.class)
			.hasMessage(BookingError.TICKET_NOT_USABLE.getMessage());
	}

	@Test
	@DisplayName("이미 취소된 티켓을 사용하면 예외가 발생한다")
	void use_whenAlreadyCancelled_throwsException() {
		// given
		Ticket ticket = TicketFixture.create(booking);
		ticket.cancel();

		// when & then
		assertThatThrownBy(ticket::use)
			.isInstanceOf(DomainException.class)
			.hasMessage(BookingError.TICKET_NOT_USABLE.getMessage());
	}
}
