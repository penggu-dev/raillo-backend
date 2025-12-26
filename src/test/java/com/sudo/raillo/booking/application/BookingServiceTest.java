package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.application.dto.request.BookingDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleWithScheduleStops;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@Slf4j
class BookingServiceTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderBookingRepository orderBookingRepository;

	@Autowired
	private OrderSeatBookingRepository orderSeatBookingRepository;

	@Autowired
	private SeatBookingRepository seatBookingRepository;

	@Test
	@DisplayName("유효한 주문으로 확정 예약 생성에 성공한다")
	void createBookingFromOrder_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleWithScheduleStops trainScheduleWithScheduleStops = trainScheduleTestHelper.createSchedule(train);
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 3);

		Order order = Order.create(member, BigDecimal.valueOf(100000));
		order.completePayment();
		Order savedOrder = orderRepository.save(order);

		OrderBooking orderBooking1 = OrderBooking.create(
			savedOrder,
			trainScheduleWithScheduleStops.trainSchedule(),
			trainScheduleWithScheduleStops.scheduleStops().get(0),
			trainScheduleWithScheduleStops.scheduleStops().get(1),
			BigDecimal.valueOf(50000)
		);
		OrderBooking savedOrderBooking1 = orderBookingRepository.save(orderBooking1);

		OrderBooking orderBooking2 = OrderBooking.create(
			savedOrder,
			trainScheduleWithScheduleStops.trainSchedule(),
			trainScheduleWithScheduleStops.scheduleStops().get(0),
			trainScheduleWithScheduleStops.scheduleStops().get(1),
			BigDecimal.valueOf(50000)
		);
		OrderBooking savedOrderBooking2 = orderBookingRepository.save(orderBooking2);

		// OrderSeatBooking 3개 생성 (orderBooking1에 좌석 2개, orderBooking2에 좌석 1개)
		OrderSeatBooking orderSeatBooking1 = OrderSeatBooking.create(savedOrderBooking1, seatIds.get(0), PassengerType.ADULT);
		OrderSeatBooking orderSeatBooking2 = OrderSeatBooking.create(savedOrderBooking1, seatIds.get(1), PassengerType.CHILD);
		OrderSeatBooking orderSeatBooking3 = OrderSeatBooking.create(savedOrderBooking2, seatIds.get(2), PassengerType.ADULT);
		orderSeatBookingRepository.saveAll(List.of(orderSeatBooking1, orderSeatBooking2, orderSeatBooking3));

		// when
		bookingService.createBookingFromOrder(savedOrder);

		// then
		List<Booking> bookings = bookingRepository.findAll();
		assertThat(bookings).hasSize(2);

		// 각 Booking 검증
		for (Booking booking : bookings) {
			assertThat(booking.getMember().getId()).isEqualTo(member.getId());
			assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.BOOKED);
			assertThat(booking.getTrainSchedule().getId()).isEqualTo(trainScheduleWithScheduleStops.trainSchedule().getId());
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
	}

	@Test
	@DisplayName("만료된 주문으로 예약 생성 시 예외가 발생한다")
	void expiredOrder_createBookingFromOrder_throwException() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Order order = OrderFixture.create(member);
		order.expired();
		orderRepository.save(order);

		// when & then
		assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.ORDER_IS_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("결제되지 않은 주문으로 예약 생성 시 예외가 발생한다")
	void pendingOrder_createBookingFromOrder_throwException() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Order order = OrderFixture.create(member);
		orderRepository.save(order);

		// when & then
		assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
			.isInstanceOf(DomainException.class)
			.hasMessage(OrderError.NOT_ORDERED.getMessage());
	}

	@Test
	@DisplayName("멤버번호와 예약 ID로 특정 예약 조회에 성공한다")
	void memberNoAndBookingId_getBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithScheduleStops trainScheduleWithScheduleStops = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, trainScheduleWithScheduleStops).booking();
		String memberNo = member.getMemberDetail().getMemberNo();

		// when
		BookingDetail result = bookingService.getBooking(memberNo, booking.getId());

		// then
		assertThat(result.bookingId()).isEqualTo(booking.getId());
		assertThat(result.bookingCode()).isEqualTo(booking.getBookingCode());

		assertThat(result.departureStationName())
			.isEqualTo(trainScheduleWithScheduleStops.scheduleStops().get(0).getStation().getStationName());

		assertThat(result.arrivalStationName())
			.isEqualTo(trainScheduleWithScheduleStops.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("올바른 멤버번호와 잘못된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndInvalidBookingId_getBooking_throwException() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithScheduleStops trainScheduleWithScheduleStops = trainScheduleTestHelper.createSchedule(train);
		bookingTestHelper.createBooking(member, trainScheduleWithScheduleStops);
		String memberNo = member.getMemberDetail().getMemberNo();

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(memberNo, 2L))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("멤버번호로 관련한 예약 목록 조회에 성공한다")
	void memberNo_getBookings_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();

		TrainScheduleWithScheduleStops BusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleWithScheduleStops DaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Booking booking1 = bookingTestHelper.createBooking(member, BusanToDongDaegu).booking();
		Booking booking2 = bookingTestHelper.createBooking(member, DaejeonToSeoul).booking();

		// when
		List<BookingDetail> result = bookingService.getBookings(member.getMemberDetail().getMemberNo());

		// then
		assertThat(result.size()).isEqualTo(2);
		BookingDetail bookingDetail1 = result.get(0);
		BookingDetail bookingDetail2 = result.get(1);

		// 첫 번째 Booking 검증
		assertThat(bookingDetail1.bookingId()).isEqualTo(booking1.getId());
		assertThat(bookingDetail1.bookingCode()).isEqualTo(booking1.getBookingCode());

		assertThat(bookingDetail1.departureStationName())
			.isEqualTo(BusanToDongDaegu.scheduleStops().get(0).getStation().getStationName());

		assertThat(bookingDetail1.arrivalStationName())
			.isEqualTo(BusanToDongDaegu.scheduleStops().get(1).getStation().getStationName());

		// 두 번째 Booking 검증
		assertThat(bookingDetail2.bookingId()).isEqualTo(booking2.getId());
		assertThat(bookingDetail2.bookingCode()).isEqualTo(booking2.getBookingCode());

		assertThat(bookingDetail2.departureStationName())
			.isEqualTo(DaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());

		assertThat(bookingDetail2.arrivalStationName())
			.isEqualTo(DaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("올바른 예약 삭제 요청 DTO로 예약 삭제에 성공한다")
	void validRequestDto_deleteBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithScheduleStops trainScheduleWithScheduleStops = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, trainScheduleWithScheduleStops).booking();
		BookingDeleteRequest request = new BookingDeleteRequest(booking.getId());

		// when
		bookingService.deleteBooking(request.bookingId());

		// then
		List<Booking> result = bookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	@DisplayName("좌석 예약 ID로 좌석 예약 삭제에 성공한다")
	void seatBookingId_deleteSeatBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithScheduleStops trainScheduleWithScheduleStops = trainScheduleTestHelper.createSchedule(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Booking booking = bookingTestHelper.createCustomBooking(member, trainScheduleWithScheduleStops)
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
	@DisplayName("예약 ID로 좌석 예약 삭제에 성공한다")
	void bookingId_deleteSeatBooking_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithScheduleStops trainScheduleWithScheduleStops = trainScheduleTestHelper.createSchedule(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Booking booking = bookingTestHelper.createCustomBooking(member, trainScheduleWithScheduleStops)
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
}
