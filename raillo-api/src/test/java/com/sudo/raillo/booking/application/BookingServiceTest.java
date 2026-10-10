package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.application.dto.request.BookingDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.exception.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.payment.application.BookingSeatReleasePayload;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;
import com.sudo.raillo.payment.domain.PaymentOutboxType;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.OrderResult;
import com.sudo.raillo.support.helper.OrderTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

@ServiceTest
@Slf4j
class BookingServiceTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private SeatBookingRepository seatBookingRepository;

	@Autowired
	private OrderBookingRepository orderBookingRepository;

	@Autowired
	private OrderSeatBookingRepository orderSeatBookingRepository;

	@Autowired
	private TicketRepository ticketRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private OrderTestHelper orderTestHelper;

	@Autowired
	private PaymentOutboxRepository paymentOutboxRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("유효한 주문으로 예매, 좌석예매, 승차권이 생성된다")
	void createBookingFromOrder_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 3);

		Order order = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.addSeat(seats.get(1), PassengerType.CHILD)
				.and()
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(2), PassengerType.ADULT)
				.and()
			.build()
			.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		List<Booking> bookings = bookingRepository.findAll();
		assertThat(bookings).hasSize(2);

		// Booking 검증
		for (Booking booking : bookings) {
			assertThat(booking.getMember().getId()).isEqualTo(member.getId());
			assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.BOOKED);
			assertThat(booking.getTrainSchedule().getId()).isEqualTo(trainScheduleResult.trainSchedule().getId());
			assertThat(booking.getBookingCode()).isNotNull();
		}

		// SeatBooking 검증
		List<SeatBooking> savedSeatBookings = seatBookingRepository.findAll();
		assertThat(savedSeatBookings).hasSize(3);

		// Booking별 SeatBooking 개수 검증
		Long booking1Id = bookings.get(0).getId();
		Long booking2Id = bookings.get(1).getId();

		List<SeatBooking> booking1SeatBookings = savedSeatBookings.stream()
			.filter(seatBooking -> seatBooking.getBooking().getId().equals(booking1Id))
			.toList();

		List<SeatBooking> booking2SeatBookings = savedSeatBookings.stream()
			.filter(seatBooking -> seatBooking.getBooking().getId().equals(booking2Id))
			.toList();

		assertThat(booking1SeatBookings).hasSize(2); // orderBooking1에서 생성된 booking1은 seatBooking 2개
		assertThat(booking2SeatBookings).hasSize(1); // orderBooking2에서 생성된 booking2는 seatBooking 1개

		// Ticket 검증
		List<Ticket> savedTickets = ticketRepository.findAll();
		assertThat(savedTickets).hasSize(3);

		// 각 Ticket 검증
		for (Ticket ticket : savedTickets) {
			assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
			assertThat(ticket.getBooking()).isNotNull();
			assertThat(ticket.getSeat()).isNotNull();
			assertThat(ticket.getFare()).isNotNull();
			assertThat(ticket.getTicketNumber()).matches("\\d{4}-\\d{7}-\\d{2}");
		}

		// Booking별 Ticket 개수 검증
		List<Ticket> booking1Tickets = savedTickets.stream()
			.filter(ticket -> ticket.getBooking().getId().equals(booking1Id))
			.toList();

		List<Ticket> booking2Tickets = savedTickets.stream()
			.filter(ticket -> ticket.getBooking().getId().equals(booking2Id))
			.toList();

		assertThat(booking1Tickets).hasSize(2); // orderBooking1에서 생성된 booking1은 ticket 2개
		assertThat(booking2Tickets).hasSize(1); // orderBooking2에서 생성된 booking2는 ticket 1개

		// PassengerType 검증
		assertThat(booking1Tickets)
			.extracting(Ticket::getPassengerType)
			.containsExactlyInAnyOrder(PassengerType.ADULT, PassengerType.CHILD);
		assertThat(booking2Tickets)
			.extracting(Ticket::getPassengerType)
			.containsExactly(PassengerType.ADULT);
	}

	@Test
	@DisplayName("만료된 주문으로 예매 생성 시 예외가 발생한다")
	void expiredOrder_createBookingFromOrder_throwException() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Order order = OrderFixture.create(member);
		order.expired();

		// when & then
		assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.ORDER_IS_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("결제되지 않은 주문으로 예매 생성 시 예외가 발생한다")
	void pendingOrder_createBookingFromOrder_throwException() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Order order = OrderFixture.create(member);

		// when & then
		assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.NOT_ORDERED.getMessage());
	}

	@Test
	@DisplayName("주문 내역(OrderBooking)이 존재하지 않으면 예외가 발생한다")
	void createBookingFromOrder_orderBookingNotFound_fail() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);

		OrderResult orderResult = orderTestHelper.createDefault(member, trainScheduleResult);
		Order order = orderResult.order();
		order.completePayment();

		// when
		orderBookingRepository.deleteAll();

		// then
		assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.ORDER_BOOKING_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("주문 좌석 내역(OrderSeatBooking)이 존재하지 않으면 예외가 발생한다")
	void createBookingFromOrder_orderSeatBookingNotFound_fail() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);

		OrderResult orderResult = orderTestHelper.createDefault(member, trainScheduleResult);
		Order order = orderResult.order();
		order.completePayment();

		// when
		orderSeatBookingRepository.deleteAll();

		// then
		assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.ORDER_SEAT_BOOKING_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("멤버번호와 예매 ID로 특정 예매 조회에 성공한다")
	void memberNoAndBookingId_getBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		Booking booking = bookingTestHelper.createDefault(member, trainScheduleResult).booking();
		String memberNo = member.getMemberDetail().getMemberNo();

		// when
		BookingResponse result = bookingService.getBooking(memberNo, booking.getId());

		// then
		assertThat(result.bookingId()).isEqualTo(booking.getId());
		assertThat(result.bookingCode()).isEqualTo(booking.getBookingCode());

		assertThat(result.departureStationName())
			.isEqualTo(trainScheduleResult.scheduleStops().get(0).getStation().getStationName());

		assertThat(result.arrivalStationName())
			.isEqualTo(trainScheduleResult.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("올바른 멤버번호와 잘못된 예매 ID로 특정 예매 조회 시 예외를 반환한다")
	void memberNoAndInvalidBookingId_getBooking_throwException() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		bookingTestHelper.createDefault(member, trainScheduleResult);
		String memberNo = member.getMemberDetail().getMemberNo();

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(memberNo, 2L))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("존재하지 않는 회원으로 예매 조회 시 예외가 발생한다")
	void getBooking_memberNotFound_fail() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		bookingTestHelper.createDefault(member, trainScheduleResult);
		String wrongMemberNo = "wrongMemberNo";

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(wrongMemberNo, 1L))
			.isInstanceOf(BusinessException.class)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("멤버번호로 관련한 예매 목록 조회에 성공한다")
	void memberNo_getBookings_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();

		TrainScheduleResult BusanToDongDaegu = trainScheduleTestHelper.builder()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now().plusDays(1))
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleResult DaejeonToSeoul = trainScheduleTestHelper.builder()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now().plusDays(1))
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		trainScheduleTestHelper.createOrUpdateStationFare("부산", "동대구", 5000, 10000);
		trainScheduleTestHelper.createOrUpdateStationFare("대전", "서울", 5000, 10000);

		Booking booking1 = bookingTestHelper.createDefault(member, BusanToDongDaegu).booking();
		Booking booking2 = bookingTestHelper.createDefault(member, DaejeonToSeoul).booking();

		// when
		List<BookingResponse> result = bookingService.getBookings(member.getMemberDetail().getMemberNo(), BookingTimeFilter.UPCOMING); // TODO : 테스트 변경 필요

		// then
		assertThat(result.size()).isEqualTo(2);
		BookingResponse bookingResponse1 = result.get(0);
		BookingResponse bookingResponse2 = result.get(1);

		// 첫 번째 Booking 검증
		assertThat(bookingResponse1.bookingId()).isEqualTo(booking1.getId());
		assertThat(bookingResponse1.bookingCode()).isEqualTo(booking1.getBookingCode());

		assertThat(bookingResponse1.departureStationName())
			.isEqualTo(BusanToDongDaegu.scheduleStops().get(0).getStation().getStationName());

		assertThat(bookingResponse1.arrivalStationName())
			.isEqualTo(BusanToDongDaegu.scheduleStops().get(1).getStation().getStationName());

		// 두 번째 Booking 검증
		assertThat(bookingResponse2.bookingId()).isEqualTo(booking2.getId());
		assertThat(bookingResponse2.bookingCode()).isEqualTo(booking2.getBookingCode());

		assertThat(bookingResponse2.departureStationName())
			.isEqualTo(DaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());

		assertThat(bookingResponse2.arrivalStationName())
			.isEqualTo(DaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("존재하지 않는 회원으로 예매 목록 조회 시 예외가 발생한다")
	void getBookings_memberNotFound_fail() {
		// given
		String wrongMemberNo = "wrongMemberNo";

		// when & then
		assertThatThrownBy(() -> bookingService.getBookings(wrongMemberNo, BookingTimeFilter.ALL))
			.isInstanceOf(BusinessException.class)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("본인 예매를 삭제하면 예매가 지워진다")
	void ownBooking_deleteBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		Booking booking = bookingTestHelper.createDefault(member, trainScheduleResult).booking();
		BookingDeleteRequest request = new BookingDeleteRequest(booking.getId());

		// when
		bookingService.deleteBooking(member.getMemberDetail().getMemberNo(), request.bookingId());

		// then
		List<Booking> result = bookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	@DisplayName("본인 예매를 삭제하면 좌석 해제 Outbox 행이 함께 생긴다")
	void ownBooking_deleteBooking_enqueuesSeatRelease() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(1, 2);
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		Booking booking = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.addSeat(seats.get(1), PassengerType.ADULT)
			.build()
			.booking();

		// when
		bookingService.deleteBooking(member.getMemberDetail().getMemberNo(), booking.getId());

		// then
		PaymentOutbox outbox = paymentOutboxRepository
			.findByDeduplicationKey(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED.deduplicationKey(booking.getId()))
			.orElseThrow();
		assertThat(outbox.getType()).isEqualTo(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED);
		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(outbox.getAggregateId()).isEqualTo(booking.getId());

		BookingSeatReleasePayload payload =
			objectMapper.readValue(outbox.getPayload(), BookingSeatReleasePayload.class);
		assertThat(payload.bookingId()).isEqualTo(booking.getId());
		assertThat(payload.trainScheduleId()).isEqualTo(trainScheduleResult.trainSchedule().getId());
		assertThat(payload.departureStopOrder()).isEqualTo(booking.getDepartureStop().getStopOrder());
		assertThat(payload.arrivalStopOrder()).isEqualTo(booking.getArrivalStop().getStopOrder());
		assertThat(payload.seats()).containsExactlyInAnyOrder(
			new BookingSeatReleasePayload.SeatEntry(seats.get(0).getId(), seats.get(0).getTrainCar().getId()),
			new BookingSeatReleasePayload.SeatEntry(seats.get(1).getId(), seats.get(1).getTrainCar().getId()));
	}

	@Test
	@DisplayName("좌석이 서로 다른 객차에 있으면 좌석마다 자기 객차 ID가 담긴다")
	void deleteBooking_mapsEachSeatToItsOwnCar() {
		// given - 좌석별 매핑은 객차가 다를 때만 검증된다. 예약 경로가 MULTIPLE_TRAIN_CARS로 금지하는
		// 상태이고, payload 빌더의 계약만 고정하려고 인위적으로 만든다
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(1, 1);
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		Seat standardSeat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);
		Seat firstClassSeat = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1).get(0);
		assertThat(standardSeat.getTrainCar().getId()).isNotEqualTo(firstClassSeat.getTrainCar().getId());

		Booking booking = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(standardSeat, PassengerType.ADULT)
			.addSeat(firstClassSeat, PassengerType.ADULT)
			.build()
			.booking();

		// when
		bookingService.deleteBooking(member.getMemberDetail().getMemberNo(), booking.getId());

		// then
		PaymentOutbox outbox = paymentOutboxRepository
			.findByDeduplicationKey(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED.deduplicationKey(booking.getId()))
			.orElseThrow();
		BookingSeatReleasePayload payload =
			objectMapper.readValue(outbox.getPayload(), BookingSeatReleasePayload.class);
		assertThat(payload.seats()).containsExactlyInAnyOrder(
			new BookingSeatReleasePayload.SeatEntry(standardSeat.getId(), standardSeat.getTrainCar().getId()),
			new BookingSeatReleasePayload.SeatEntry(firstClassSeat.getId(), firstClassSeat.getTrainCar().getId()));
	}

	@Test
	@DisplayName("좌석 예매가 없는 예매도 삭제되고 좌석 해제 Outbox 행은 생기지 않는다")
	void deleteBooking_withoutSeatBookings_skipsSeatRelease() {
		// given - 데이터 이상으로 `SeatBooking`이 비어 있는 `Booking`
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		Booking booking = bookingTestHelper.createDefault(member, trainScheduleResult).booking();
		// 테스트 메서드에 @Transactional을 쓸 수 없어 JPA 대신 직접 지운다
		jdbcTemplate.update("delete from ticket where booking_id = ?", booking.getId());
		jdbcTemplate.update("delete from seat_booking where booking_id = ?", booking.getId());

		// when
		bookingService.deleteBooking(member.getMemberDetail().getMemberNo(), booking.getId());

		// then
		assertThat(bookingRepository.findById(booking.getId())).isEmpty();
		assertThat(paymentOutboxRepository.findByDeduplicationKey(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED.deduplicationKey(booking.getId())))
			.isEmpty();
	}

	@Test
	@DisplayName("다른 회원의 예매를 삭제하면 접근 권한 예외가 발생하고 예매가 남는다")
	void otherMembersBooking_deleteBooking_fail() {
		// given
		Member owner = memberRepository.save(MemberFixture.create());
		Member other = memberRepository.save(MemberFixture.createOther());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		Booking booking = bookingTestHelper.createDefault(owner, trainScheduleResult).booking();

		// when & then
		assertThatThrownBy(() -> bookingService.deleteBooking(other.getMemberDetail().getMemberNo(), booking.getId()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_ACCESS_DENIED.getMessage());

		assertThat(bookingRepository.findById(booking.getId())).isPresent();
		assertThat(paymentOutboxRepository.findByDeduplicationKey(PaymentOutboxType.BOOKING_SEAT_RELEASE_REQUIRED.deduplicationKey(booking.getId())))
			.isEmpty();
	}

	@Test
	@DisplayName("존재하지 않는 예매 ID로 삭제하면 예매 조회 예외가 발생한다")
	void unknownBookingId_deleteBooking_fail() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		long unknownBookingId = 999_999L;

		// when & then
		assertThatThrownBy(() -> bookingService.deleteBooking(member.getMemberDetail().getMemberNo(), unknownBookingId))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("존재하지 않는 회원번호로 삭제하면 회원 조회 예외가 발생한다")
	void unknownMemberNo_deleteBooking_fail() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		Booking booking = bookingTestHelper.createDefault(member, trainScheduleResult).booking();

		// when & then
		assertThatThrownBy(() -> bookingService.deleteBooking("wrongMemberNo", booking.getId()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("좌석 예매 ID로 예매 좌석 삭제에 성공한다")
	void seatBookingId_deleteSeatBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Booking booking = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.addSeat(seats.get(1), PassengerType.ADULT)
			.build()
			.booking();

		List<SeatBooking> seatBookings = seatBookingRepository.findByBookingId(booking.getId());

		// when
		bookingService.deleteSeatBooking(seatBookings.get(0).getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(1);
		assertThat(result.get(0).getPassengerType()).isEqualTo(PassengerType.ADULT);
	}

	@Test
	@DisplayName("존재하지 않는 예매 좌석 삭제 시 예외가 발생한다")
	void deleteSeatBooking_notFound_fail() {
		// given
		Long wrongSeatBookingId = 9999L;

		// when & then
		assertThatThrownBy(() -> bookingService.deleteSeatBooking(wrongSeatBookingId))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.SEAT_BOOKING_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("예매 ID로 예매 좌석 삭제에 성공한다")
	void bookingId_deleteSeatBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Booking booking = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.addSeat(seats.get(1), PassengerType.ADULT)
			.build()
			.booking();

		// when
		bookingService.deleteSeatBookingByBookingId(booking.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	@DisplayName("예매 생성 시 SeatBooking의 역정규화 필드(역ID, 정차순서, 객차타입)가 설정된다")
	void createBookingFromOrder_seatBooking_denormalized_fields_set() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);

		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		Order order = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.and()
			.build()
			.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		List<SeatBooking> seatBookings = seatBookingRepository.findAll();
		assertThat(seatBookings).hasSize(1);

		SeatBooking seatBooking = seatBookings.get(0);

		Long departureStationId = trainScheduleResult.scheduleStops().get(0).getStation().getId();
		Long arrivalStationId = trainScheduleResult.scheduleStops().get(1).getStation().getId();

		assertThat(seatBooking.getDepartureStationId()).isEqualTo(departureStationId);
		assertThat(seatBooking.getArrivalStationId()).isEqualTo(arrivalStationId);
		assertThat(seatBooking.getDepartureStopOrder()).isEqualTo(0);
		assertThat(seatBooking.getArrivalStopOrder()).isEqualTo(1);
		assertThat(seatBooking.getCarType()).isEqualTo(CarType.STANDARD);
	}

	@Test
	@DisplayName("중간 정차역 구간 예매 시 SeatBooking의 역정규화 필드가 올바르게 설정된다")
	void createBookingFromOrder_seatBooking_intermediate_stops_denormalized() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();

		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("test")
			.operationDate(LocalDate.now().plusDays(1))
			.train(train)
			.addStop("서울", null, LocalTime.of(6, 0))
			.addStop("대전", LocalTime.of(7, 30), LocalTime.of(7, 35))
			.addStop("동대구", LocalTime.of(8, 30), LocalTime.of(8, 35))
			.addStop("부산", LocalTime.of(9, 30), null)
			.build();

		trainScheduleTestHelper.createOrUpdateStationFare("대전", "동대구", 15000, 25000);

		// 대전 -> 동대구 구간으로 예매 (중간 정차역 테스트)
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		List<Seat> firstClassSeats = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1);

		ScheduleStop departureStop = trainScheduleTestHelper.getScheduleStopByStationName(trainScheduleResult, "대전");
		ScheduleStop arrivalStop = trainScheduleTestHelper.getScheduleStopByStationName(trainScheduleResult, "동대구");

		Order order = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.setDepartureScheduleStop(departureStop)
				.setArrivalScheduleStop(arrivalStop)
				.addSeat(standardSeats.get(0), PassengerType.ADULT)
				.and()
			.addOrderBooking(trainScheduleResult)
				.setDepartureScheduleStop(departureStop)
				.setArrivalScheduleStop(arrivalStop)
				.addSeat(firstClassSeats.get(0), PassengerType.CHILD)
				.and()
			.build()
			.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		List<SeatBooking> seatBookings = seatBookingRepository.findAll();
		assertThat(seatBookings).hasSize(2);

		Long departureStationId = trainScheduleResult.scheduleStops().get(1).getStation().getId(); // 대전
		Long arrivalStationId = trainScheduleResult.scheduleStops().get(2).getStation().getId(); // 동대구

		// 출발역/도착역 ID, stopOrder 검증
		for (SeatBooking seatBooking : seatBookings) {
			assertThat(seatBooking.getDepartureStationId()).isEqualTo(departureStationId);
			assertThat(seatBooking.getArrivalStationId()).isEqualTo(arrivalStationId);
			assertThat(seatBooking.getDepartureStopOrder()).isEqualTo(1); // 대전은 stopOrder 1
			assertThat(seatBooking.getArrivalStopOrder()).isEqualTo(2); // 동대구는 stopOrder 2
		}

		// CarType 검증
		assertThat(seatBookings.get(0).getCarType()).isEqualTo(CarType.STANDARD);
		assertThat(seatBookings.get(1).getCarType()).isEqualTo(CarType.FIRST_CLASS);
	}
}
