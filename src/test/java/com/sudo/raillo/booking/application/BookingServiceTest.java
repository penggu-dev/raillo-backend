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
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper.TrainScheduleWithStopStations;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
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

	private Member member;
	private Train train;
	private TrainScheduleWithStopStations schedule;

	@BeforeEach
	void setup() {
		Member member = MemberFixture.createStandardMember();
		this.member = memberRepository.save(member);
		train = trainTestHelper.createCustomKTX(3, 2);
		schedule = trainScheduleTestHelper.createSchedule(train);
	}

	@Test
	@DisplayName("유효한 주문으로 확정 예약 생성에 성공한다")
	void createBookingFromOrder_success() {
		// given
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 3);

		Order order = Order.create(member, BigDecimal.valueOf(100000));
		order.completePayment();
		Order savedOrder = orderRepository.save(order);

		OrderBooking orderBooking1 = OrderBooking.create(
			savedOrder,
			schedule.trainSchedule(),
			schedule.scheduleStops().get(0),
			schedule.scheduleStops().get(1),
			BigDecimal.valueOf(50000)
		);
		OrderBooking savedOrderBooking1 = orderBookingRepository.save(orderBooking1);

		OrderBooking orderBooking2 = OrderBooking.create(
			savedOrder,
			schedule.trainSchedule(),
			schedule.scheduleStops().get(0),
			schedule.scheduleStops().get(1),
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
			assertThat(booking.getTrainSchedule().getId()).isEqualTo(schedule.trainSchedule().getId());
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
		Order order = Order.create(member, BigDecimal.valueOf(50000));
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
		Order order = Order.create(member, BigDecimal.valueOf(50000));
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
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, schedule);
		Booking entity = bookingRepository.save(booking);

		// when
		BookingDetail result = bookingService.getBooking(memberNo, entity.getId());

		// then
		assertThat(result.bookingId()).isEqualTo(entity.getId());
		assertThat(result.bookingCode()).isEqualTo(booking.getBookingCode());
		assertThat(result.departureStationName()).isEqualTo(
			schedule.scheduleStops().get(0).getStation().getStationName());
		assertThat(result.arrivalStationName()).isEqualTo(
			schedule.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("올바른 멤버번호와 잘못된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndInvalidBookingId_getBooking_throwException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, schedule);
		bookingRepository.save(booking);

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(memberNo, 2L))
			.isInstanceOf(BusinessException.class);

		bookingRepository.save(booking);
	}

	@Test
	@DisplayName("올바른 멤버번호와 만료된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndExpiredBookingId_getBooking_throwException() {
		/*// given
		String memberNo = member.getMemberDetail().getMemberNo();
		Booking booking = Booking.builder()
			.trainSchedule(schedule.trainSchedule())
			.member(member)
			.bookingCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().minusMinutes(10))
			.fare(50000)
			.departureStop(schedule.scheduleStops().get(0))
			.arrivalStop(schedule.scheduleStops().get(1))
			.build();
		Booking entity = bookingRepository.save(booking);

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(memberNo, entity.getId()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_EXPIRED.getMessage());*/
	}

	@Test
	@DisplayName("멤버번호로 관련한 예약 목록 조회에 성공한다")
	void memberNo_getBookings_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleBusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleDaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Booking booking1 = bookingTestHelper.createBooking(member, scheduleBusanToDongDaegu);
		Booking booking2 = bookingTestHelper.createBooking(member, scheduleDaejeonToSeoul);
		Booking entity1 = bookingRepository.save(booking1);
		Booking entity2 = bookingRepository.save(booking2);

		// when
		List<BookingDetail> result = bookingService.getBookings(memberNo);

		// then
		assertThat(result.size()).isEqualTo(2);
		BookingDetail result1 = result.get(0);
		BookingDetail result2 = result.get(1);

		assertThat(result1.bookingId()).isEqualTo(entity1.getId());
		assertThat(result1.bookingCode()).isEqualTo(booking1.getBookingCode());
		assertThat(result1.departureStationName()).isEqualTo(
			scheduleBusanToDongDaegu.scheduleStops().get(0).getStation().getStationName());
		assertThat(result1.arrivalStationName()).isEqualTo(
			scheduleBusanToDongDaegu.scheduleStops().get(1).getStation().getStationName());

		assertThat(result2.bookingId()).isEqualTo(entity2.getId());
		assertThat(result2.bookingCode()).isEqualTo(booking2.getBookingCode());
		assertThat(result2.departureStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());
		assertThat(result2.arrivalStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("멤버번호로 예약 목록 조회 시 만료된 예약을 제외하고 조회에 성공한다")
	void memberNoAndExpiredBooking_getBookings_success() {
		/*// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleBusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleDaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Booking booking1 = Booking.builder()
			.trainSchedule(scheduleBusanToDongDaegu.trainSchedule())
			.member(member)
			.bookingCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().minusMinutes(10))
			.fare(50000)
			.departureStop(scheduleBusanToDongDaegu.scheduleStops().get(0))
			.arrivalStop(scheduleBusanToDongDaegu.scheduleStops().get(1))
			.build();
		Booking booking2 = bookingTestHelper.createPendingBooking(member, scheduleDaejeonToSeoul);
		Booking entity1 = bookingRepository.save(booking1);
		Booking entity2 = bookingRepository.save(booking2);

		// when
		List<BookingDetail> result = bookingService.getBookings(memberNo);

		// then
		assertThat(result.size()).isEqualTo(1);
		BookingDetail result1 = result.get(0);

		assertThat(result1.bookingId()).isEqualTo(entity2.getId());
		assertThat(result1.bookingCode()).isEqualTo(booking2.getBookingCode());
		assertThat(result1.departureStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());
		assertThat(result1.arrivalStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());*/
	}

	@Test
	@DisplayName("올바른 예약 삭제 요청 DTO로 예약 삭제에 성공한다")
	void validRequestDto_deleteBooking_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, schedule);
		Booking entity = bookingRepository.save(booking);
		BookingDeleteRequest request = new BookingDeleteRequest(entity.getId());

		// when
		bookingService.deleteBooking(request.bookingId());

		// then
		List<Booking> result = bookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	@DisplayName("예약, 좌석, 승객 유형으로 좌석 예약 생성에 성공한다")
	void bookingAndSeatAndPassengerType_reserveNewSeat_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createOnlyBooking(member, schedule);
		Seat seat = trainTestHelper.getSeats(train, CarType.STANDARD, 1).get(0);

		// when
		SeatBooking entity = bookingService.reserveNewSeat(booking, seat, PassengerType.CHILD);

		// then
		assertThat(entity.getBooking().getBookingCode()).isEqualTo(booking.getBookingCode());
		assertThat(entity.getPassengerType()).isEqualTo(PassengerType.CHILD);
	}

	@Test
	@DisplayName("좌석 예약 ID로 좌석 예약 삭제에 성공한다")
	void seatBookingId_deleteSeatBooking_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainSchedule trainSchedule = trainScheduleTestHelper.createSchedule(train).trainSchedule();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createOnlyBooking(member, schedule);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		SeatBooking seatBooking1 = SeatBooking.create(
			trainSchedule,
			seats.get(0),
			booking,
			PassengerType.CHILD
		);
		SeatBooking savedSeatBooking = seatBookingRepository.save(seatBooking1);

		SeatBooking seatBooking2 = SeatBooking.create(
			trainSchedule,
			seats.get(1),
			booking,
			PassengerType.VETERAN
		);
		seatBookingRepository.save(seatBooking2);

		// when
		bookingService.deleteSeatBooking(savedSeatBooking.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(1);
		assertThat(result.get(0).getPassengerType()).isEqualTo(PassengerType.VETERAN);
	}

	@Test
	@DisplayName("예약 ID로 좌석 예약 삭제에 성공한다")
	void bookingId_deleteSeatBooking_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainSchedule trainSchedule = trainScheduleTestHelper.createSchedule(train).trainSchedule();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createOnlyBooking(member, schedule);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		SeatBooking seatBooking1 = SeatBooking.create(
			trainSchedule,
			seats.get(0),
			booking,
			PassengerType.CHILD
		);
		seatBookingRepository.save(seatBooking1);

		SeatBooking seatBooking2 = SeatBooking.create(
			trainSchedule,
			seats.get(1),
			booking,
			PassengerType.VETERAN
		);
		seatBookingRepository.save(seatBooking2);

		// when
		bookingService.deleteSeatBookingByBookingId(booking.getId());

		// then
		List<SeatBooking> result = seatBookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

}
