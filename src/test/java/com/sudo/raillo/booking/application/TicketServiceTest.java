package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingResult;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@Slf4j
class TicketServiceTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private TicketRepository ticketRepository;

	@Autowired
	private TicketService ticketService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setup() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	// @Disabled("Booking 생성 시 Ticket이 자동 생성되도록 변경됨(BookingTestHelper). createTicket()의 외부 호출 케이스 확인 후 테스트 수정 또는 삭제 필요")
	@DisplayName("예약, 좌석, 승객 유형으로 티켓 생성에 성공한다")
	void bookingAndSeatAndPassengerType_createTicket_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.withoutTickets()  // Ticket 없이 Booking만 생성
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		Seat newSeat = trainTestHelper.getSeats(train, CarType.STANDARD, 2).get(1);

		// when
		ticketService.createTicket(bookingResult.booking(), newSeat, PassengerType.CHILD);

		// then
		List<Ticket> result = ticketRepository.findAll();
		assertThat(result).hasSize(1);
		assertThat(result.get(0).getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
	}

	@Test
	@DisplayName("멤버번호로 가지고 있는 티켓 조회에 성공한다")
	void memberNo_getMyTickets_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.CHILD)
			.addSeat(seats.get(1), PassengerType.VETERAN)
			.build();

		// when
		List<TicketReadResponse> result = ticketService.getMyTickets(member.getMemberDetail().getMemberNo());

		// then
		assertThat(result).hasSize(2);
	}

	@Test
	@DisplayName("티켓 ID로 티켓 삭제에 성공한다")
	void ticketId_deleteTicket_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.CHILD)
			.addSeat(seats.get(1), PassengerType.VETERAN)
			.build();

		Ticket ticketToDelete = bookingResult.tickets().get(0);

		// when
		ticketService.deleteTicketById(ticketToDelete.getId());

		// then
		List<Ticket> remaining = ticketRepository.findAll();
		assertThat(remaining).hasSize(1);
	}

	@Test
	@DisplayName("예약 ID로 티켓 삭제에 성공한다")
	void bookingId_deleteTicket_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.CHILD)
			.addSeat(seats.get(1), PassengerType.VETERAN)
			.build();

		// when
		ticketService.deleteTicketByBookingId(bookingResult.booking().getId());

		// then
		List<Ticket> remaining = ticketRepository.findAll();
		assertThat(remaining).hasSize(0);
	}
}
