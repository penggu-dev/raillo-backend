package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.dto.response.TicketDetail;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
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

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 예매 조회 시 시간/승차권 상태 필터링 테스트
 */
@ServiceTest
@Slf4j
@Transactional
class BookingServiceSearchFilterTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	private LocalDate tomorrow;
	private LocalDate yesterday;
	private Member member;
	private Train train;
	private TrainScheduleResult futureSchedule;
	private TrainScheduleResult pastSchedule;

	@BeforeEach
	void setUp() {
		tomorrow = LocalDate.now().plusDays(1);
		yesterday = LocalDate.now().minusDays(1);

		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createKTX();

		// 미래 출발 (내일)
		futureSchedule = trainScheduleTestHelper.builder()
			.scheduleName("미래 노선")
			.operationDate(tomorrow)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		// 과거 출발 (어제)
		pastSchedule = trainScheduleTestHelper.builder()
			.scheduleName("과거 노선")
			.operationDate(yesterday)
			.train(train)
			.addStop("서울", null, LocalTime.of(10, 0))
			.addStop("부산", LocalTime.of(13, 0), null)
			.build();

		trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 5000, 10000);
	}

	@Test
	@DisplayName("UPCOMING 필터로 현재 시간 이후 출발 예매만 조회된다")
	void upcomingFilter_getBookings_returnsFutureBookings() {
		// given
		bookingTestHelper.createDefault(member, futureSchedule);
		bookingTestHelper.createDefault(member, pastSchedule);

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.UPCOMING
		);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).operationDate()).isEqualTo(tomorrow);
		assertThat(result.get(0).tickets())
			.extracting(TicketDetail::status)
			.containsOnly(TicketStatus.ISSUED);
	}

	@Test
	@DisplayName("HISTORY 필터로 현재 시간 이전 출발 예매만 조회된다")
	void historyFilter_getBookings_returnsPastBookings() {
		// given
		bookingTestHelper.createDefault(member, futureSchedule);
		bookingTestHelper.createDefault(member, pastSchedule);

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.HISTORY
		);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).operationDate()).isEqualTo(yesterday);
		assertThat(result.get(0).tickets())
			.extracting(TicketDetail::status)
			.containsOnly(TicketStatus.ISSUED);
	}

	@Test
	@DisplayName("ALL 필터로 BOOKED 상태의 전체 예매가 조회된다")
	void allFilter_getBookings_returnsAllBookedBookings() {
		// given
		bookingTestHelper.createDefault(member, futureSchedule);
		bookingTestHelper.createDefault(member, pastSchedule);

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.ALL
		);

		// then
		assertThat(result).hasSize(2);
	}

	@Test
	@DisplayName("HISTORY 필터로 CANCELLED 상태의 예매도 조회된다")
	void historyFilter_getBookings_includesCancelledBookings() {
		// given
		Booking booking = bookingTestHelper.createDefault(member, pastSchedule).booking();
		booking.cancel(); // 예매 취소
		em.flush();

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.HISTORY
		);

		// then
		assertThat(result).hasSize(1);
	}

	/**
	 * TODO : 취소 관련은 추후 Service에서 개별 승차권 취소 로직 구현 및 해당 호출로 변경 필요 + em.flush() 제거, @Transactional 제거
	 * - TickstStatus.isCancellable 호출 -> ticket.cancel 을 호출하는 것으로 테스트 변경 필요
	 */
	@Test
	@DisplayName("UPCOMING 조회 시 CANCELLED 상태 승차권은 제외된다")
	void upcomingFilter_getBookings_excludesCancelledTickets() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		BookingResult bookingResult = bookingTestHelper.builder(member, futureSchedule)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.addSeat(seats.get(1), PassengerType.CHILD)
			.build();

		// 첫 번째 승차권 취소
		Ticket ticketToCancel = bookingResult.tickets().get(0);
		ticketToCancel.cancel();
		em.flush();

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.UPCOMING
		);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).tickets()).hasSize(1); // 취소된 승차권 제외
		assertThat(result.get(0).tickets())
			.extracting(TicketDetail::status)
			.containsExactly(TicketStatus.ISSUED);
	}

	@Test
	@DisplayName("HISTORY 조회 시 CANCELLED 상태 승차권도 포함된다")
	void historyFilter_getBookings_includesCancelledTickets() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		BookingResult bookingResult = bookingTestHelper.builder(member, pastSchedule)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.addSeat(seats.get(1), PassengerType.CHILD)
			.build();

		// 첫 번째 승차권 취소
		Ticket ticketToCancel = bookingResult.tickets().get(0);
		ticketToCancel.cancel();
		em.flush();

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.HISTORY
		);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).tickets()).hasSize(2); // 취소된 승차권도 포함
		assertThat(result.get(0).tickets())
			.extracting(TicketDetail::status)
			.containsExactlyInAnyOrder(TicketStatus.ISSUED, TicketStatus.CANCELLED);
	}

	@Test
	@DisplayName("HISTORY 필터로 USED 상태의 승차권도 조회된다")
	void historyFilter_getBookings_includesUsedTickets() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		BookingResult bookingResult = bookingTestHelper.builder(member, pastSchedule)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		// 승차권 사용 처리
		Ticket ticket = bookingResult.tickets().get(0);
		ticket.use();
		em.flush();

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.HISTORY
		);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).tickets())
			.extracting(TicketDetail::status)
			.containsExactly(TicketStatus.USED);
	}

	@Test
	@DisplayName("ALL 필터로 USED 상태의 승차권도 조회된다")
	void allFilter_getBookings_includesUsedTickets() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		BookingResult bookingResult = bookingTestHelper.builder(member, pastSchedule)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		// 승차권 사용 처리
		Ticket ticket = bookingResult.tickets().get(0);
		ticket.use();
		em.flush();

		// when
		List<BookingResponse> result = bookingService.getBookings(
			member.getMemberDetail().getMemberNo(),
			BookingTimeFilter.ALL
		);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).tickets())
			.extracting(TicketDetail::status)
			.containsExactly(TicketStatus.USED);
	}
}
