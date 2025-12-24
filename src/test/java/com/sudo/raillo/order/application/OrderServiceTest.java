package com.sudo.raillo.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper.TrainScheduleWithStopStations;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class OrderServiceTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderBookingRepository orderBookingRepository;

	@Autowired
	private OrderSeatBookingRepository orderSeatBookingRepository;

	@Autowired
	private OrderService orderService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Test
	@DisplayName("주문 코드로 주문 조회에 성공한다")
	void getOrderByCreateOrderCode_success() {
		// given
		Member member = MemberFixture.create();
		memberRepository.save(member);
		Order order = Order.create(member, BigDecimal.ZERO);
		Order savedOrder = orderRepository.save(order);

		// when
		Order foundOrder = orderService.getOrderByOrderCode(savedOrder.getOrderCode());

		// then
		assertThat(foundOrder.getId()).isEqualTo(savedOrder.getId());
		assertThat(foundOrder.getOrderCode()).isEqualTo(savedOrder.getOrderCode());
	}

	@Test
	@DisplayName("존재하지 않는 주문 코드로 조회 시 예외가 발생한다")
	void getOrderByOrderCode_fail_when_createOrder_not_found() {
		// given
		String wrongOrderCode = "WRONG_ORDER_CODE";

		// when & then
		assertThatThrownBy(() -> orderService.getOrderByOrderCode(wrongOrderCode))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.ORDER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("주문 소유자가 일치하지 않으면 예외가 발생한다")
	void validateCreateOrderOwner_fail_when_owner_mismatch() {
		// given
		Member member = MemberFixture.create();
		memberRepository.save(member);

		Member otherMember = MemberFixture.createOther();
		memberRepository.save(otherMember);

		Order order = Order.create(member, BigDecimal.valueOf(50000));
		orderRepository.save(order);

		// when & then
		assertThatThrownBy(() -> orderService.validateOrderOwner(order, otherMember))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.ORDER_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("주문 소유자가 일치하면 예외가 발생하지 않는다")
	void validateCreateOrderOwner_success() {
		// given
		Member member = MemberFixture.create();
		memberRepository.save(member);
		Order order = Order.create(member, BigDecimal.valueOf(50000));
		orderRepository.save(order);

		// when & then
		assertThatNoException()
			.isThrownBy(() -> orderService.validateOrderOwner(order, member));
	}

	@Test
	@DisplayName("주문 생성에 성공한다")
	void createOrder_success() {
		// given
		Member member = MemberFixture.create();
		memberRepository.save(member);
		String memberNo = member.getMemberDetail().getMemberNo();
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithStopStations scheduleAndStation = trainScheduleTestHelper.createSchedule(train);
		Long trainScheduleId = scheduleAndStation.trainSchedule().getId();
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 2);
		Station departureStation = trainScheduleTestHelper.getOrCreateStation("서울");
		Station arrivalStation = trainScheduleTestHelper.getOrCreateStation("부산");

		PendingBooking pendingBooking = PendingBooking.create(
			memberNo,
			trainScheduleId,
			departureStation.getId(),
			arrivalStation.getId(),
			List.of(
				new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT),
				new PendingSeatBooking(seatIds.get(1), PassengerType.CHILD)
			),
			BigDecimal.valueOf(10000)
		);

		List<PendingBooking> pendingBookings = List.of(pendingBooking);

		// when
		orderService.createOrder(memberNo, pendingBookings);

		// then
		List<Order> orders = orderRepository.findAll();
		assertThat(orders).hasSize(1);

		// Order 검증
		Order savedOrder = orders.get(0);
		assertThat(savedOrder.getMember().getId()).isEqualTo(member.getId());
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

		// OrderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findAll();
		assertThat(orderBookings).hasSize(1);

		OrderBooking savedOrderBooking = orderBookings.get(0);
		assertThat(savedOrderBooking.getOrder().getId()).isEqualTo(savedOrder.getId());
		assertThat(savedOrderBooking.getTrainSchedule().getId()).isEqualTo(trainScheduleId);
		assertThat(savedOrderBooking.getDepartureStop().getId()).isEqualTo(departureStation.getId());
		assertThat(savedOrderBooking.getArrivalStop().getId()).isEqualTo(arrivalStation.getId());

		// OrderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository.findAll();
		assertThat(orderSeatBookings).hasSize(2);

		assertThat(orderSeatBookings)
			.extracting(OrderSeatBooking::getSeatId)
			.containsExactlyInAnyOrder(1L, 2L);
		assertThat(orderSeatBookings)
			.extracting(OrderSeatBooking::getPassengerType)
			.containsExactlyInAnyOrder(PassengerType.ADULT, PassengerType.CHILD);
	}

	@Test
	@DisplayName("여러 개의 PendingBooking으로 주문 생성에 성공한다")
	void createOrder_success_with_multiple_pending_bookings() {
		// given
		Member member = MemberFixture.create();
		memberRepository.save(member);
		String memberNo = member.getMemberDetail().getMemberNo();
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithStopStations scheduleAndStation = trainScheduleTestHelper.createSchedule(train);
		Long trainScheduleId = scheduleAndStation.trainSchedule().getId();
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 2);
		Station departureStation = trainScheduleTestHelper.getOrCreateStation("서울");
		Station arrivalStation = trainScheduleTestHelper.getOrCreateStation("부산");

		PendingBooking pendingBooking1 = PendingBooking.create(
			memberNo,
			trainScheduleId,
			departureStation.getId(),
			arrivalStation.getId(),
			List.of(new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)),
			BigDecimal.valueOf(30000)
		);

		PendingBooking pendingBooking2 = PendingBooking.create(
			memberNo,
			trainScheduleId,
			departureStation.getId(),
			arrivalStation.getId(),
			List.of(new PendingSeatBooking(seatIds.get(1), PassengerType.SENIOR)),
			BigDecimal.valueOf(20000)
		);

		List<PendingBooking> pendingBookings = List.of(pendingBooking1, pendingBooking2);

		// when
		orderService.createOrder(memberNo, pendingBookings);

		// then
		List<Order> orders = orderRepository.findAll();
		assertThat(orders).hasSize(1);

		Order savedOrder = orders.get(0);
		assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(50000));

		List<OrderBooking> orderBookings = orderBookingRepository.findAll();
		assertThat(orderBookings).hasSize(2);

		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository.findAll();
		assertThat(orderSeatBookings).hasSize(2);
		assertThat(orderSeatBookings)
			.extracting(OrderSeatBooking::getPassengerType)
			.containsExactlyInAnyOrder(PassengerType.ADULT, PassengerType.SENIOR);
	}

	@Test
	@DisplayName("존재하지 않는 회원 번호로 주문 생성 시 예외가 발생한다")
	void createOrder_fail_when_member_not_found() {
		// given
		String nonExistentMemberNo = "999999999999";
		Train train = trainTestHelper.createKTX();
		TrainScheduleWithStopStations scheduleAndStation = trainScheduleTestHelper.createSchedule(train);
		Long trainScheduleId = scheduleAndStation.trainSchedule().getId();
		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 1);
		Station departureStation = trainScheduleTestHelper.getOrCreateStation("서울");
		Station arrivalStation = trainScheduleTestHelper.getOrCreateStation("부산");

		PendingBooking pendingBooking = PendingBooking.create(
			nonExistentMemberNo,
			trainScheduleId,
			departureStation.getId(),
			arrivalStation.getId(),
			List.of(new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)),
			BigDecimal.valueOf(50000)
		);

		List<PendingBooking> pendingBookings = List.of(pendingBooking);

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(nonExistentMemberNo, pendingBookings))
			.isInstanceOf(BusinessException.class)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("빈 예약 리스트로 주문 생성 시 예외가 발생한다")
	void createOrder_fail_when_pending_bookings_empty() {
		// given
		Member member = MemberFixture.create();
		memberRepository.save(member);
		String memberNo = member.getMemberDetail().getMemberNo();

		List<PendingBooking> emptyPendingBookings = Collections.emptyList();

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(memberNo, emptyPendingBookings))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.EMPTY_PENDING_BOOKINGS.getMessage());
	}
}
