package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@ServiceTest
@Transactional
public class TicketNumberTest {

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TicketRepository ticketRepository;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private OrderService orderService;

	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMdd");

	@Test
	@DisplayName("승차권 번호가 순서대로 올바르게 생성된다")
	void create_tickets_single_order_sequence() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 6);
		String memberNo = member.getMemberDetail().getMemberNo();

		PendingBooking pendingBooking1 = createPendingBooking(member, trainScheduleResult, seats.subList(0, 3));
		PendingBooking pendingBooking2 = createPendingBooking(member, trainScheduleResult, seats.subList(3, 6));

		// when
		// 예약 -> 주문 -> 결제 -> 예매로 티켓 생성
		List<PendingBooking> pendingBookings = List.of(pendingBooking1, pendingBooking2);
		Order order = orderService.createOrder(memberNo, pendingBookings);
		order.completePayment();
		bookingService.createBookingFromOrder(order);

		// then
		List<Ticket> tickets = ticketRepository.findAll();
		tickets.sort(Comparator.comparing(Ticket::getTicketNumber));
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		assertThat(tickets).extracting(Ticket::getTicketNumber)
			.containsExactly(
				// 첫번째 예약
				today + "-0000001-01",
				today + "-0000001-02",
				today + "-0000001-03",
				// 두번째 예약
				today + "-0000002-01",
				today + "-0000002-02",
				today + "-0000002-03"
			);

		assertThat(tickets).extracting(Ticket::getSeat)
			.containsExactly(
				// 첫번째 예약
				seats.get(0),
				seats.get(1),
				seats.get(2),
				// 두번째 예약
				seats.get(3),
				seats.get(4),
				seats.get(5)
			);
	}

	@Test
	@DisplayName("서로 다른 예약에서 생성된 예매의 승차권 번호는 독립적으로 증가한다")
	void create_tickets_distinct_order_sequence() {
		// given
		Member member1 = memberRepository.save(MemberFixture.create());
		Member member2 = memberRepository.save(MemberFixture.createOther());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 6);
		String member1No = member1.getMemberDetail().getMemberNo();
		String member2No = member2.getMemberDetail().getMemberNo();

		// 회원1 예약 (좌석 0, 1, 2)
		PendingBooking pendingBooking1 = createPendingBooking(member1, trainScheduleResult, seats.subList(0, 3));
		// 회원2 예약 (좌석 3, 4, 5)
		PendingBooking pendingBooking2 = createPendingBooking(member2, trainScheduleResult, seats.subList(3, 6));

		// when
		// 회원1: 예약 -> 주문 -> 결제 -> 예매
		Order order1 = orderService.createOrder(member1No, List.of(pendingBooking1));
		order1.completePayment();
		bookingService.createBookingFromOrder(order1);

		// 회원2: 예약 -> 주문 -> 결제 -> 예매
		Order order2 = orderService.createOrder(member2No, List.of(pendingBooking2));
		order2.completePayment();
		bookingService.createBookingFromOrder(order2);

		// then
		List<Ticket> tickets = ticketRepository.findAll();
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		// 회원1 티켓
		List<Ticket> member1Tickets = tickets.stream()
			.filter(t -> t.getBooking().getMember().getMemberDetail().getMemberNo().equals(member1No))
			.sorted(Comparator.comparing(Ticket::getTicketNumber))
			.toList();

		assertThat(member1Tickets).extracting(Ticket::getTicketNumber)
			.containsExactly(
				today + "-0000001-01",
				today + "-0000001-02",
				today + "-0000001-03"
			);

		// 회원2 티켓
		List<Ticket> member2Tickets = tickets.stream()
			.filter(t -> t.getBooking().getMember().getMemberDetail().getMemberNo().equals(member2No))
			.sorted(Comparator.comparing(Ticket::getTicketNumber))
			.toList();

		assertThat(member2Tickets).extracting(Ticket::getTicketNumber)
			.containsExactly(
				today + "-0000002-01",
				today + "-0000002-02",
				today + "-0000002-03"
			);
	}

	private PendingBooking createPendingBooking(Member member, TrainScheduleResult result, List<Seat> seats) {
		return PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(result.trainSchedule().getId())
			.withDepartureStopId(result.scheduleStops().get(0).getId())
			.withArrivalStopId(result.scheduleStops().get(1).getId())
			.withPendingSeatBookings(seats.stream()
				.map(seat -> new PendingSeatBooking(seat.getId(), PassengerType.ADULT))
				.toList())
			.build();
	}
}
