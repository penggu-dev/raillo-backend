package com.sudo.raillo.support.helper;

import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class OrderTestHelper {

	private final TrainTestHelper trainTestHelper;
	private final OrderRepository orderRepository;
	private final OrderBookingRepository orderBookingRepository;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final FareCalculator fareCalculator;

	@Lazy
	@Autowired
	private OrderTestHelper self;

	/**
	 * 기본 주문 생성 메서드
	 *
	 * <p>출발역에서 도착역까지 성인 1명, 일반석 1좌석으로 주문을 생성한다.</p>
	 *
	 * @param member 주문할 회원
	 * @param trainScheduleResult 열차 스케줄 및 정차역 정보
	 * @return 생성된 주문 결과 (Order, OrderBookings, OrderSeatBookings)
	 */
	public OrderResult createDefault(Member member, TrainScheduleResult trainScheduleResult) {
		return builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeatsByCarType(CarType.STANDARD, 1, PassengerType.ADULT)
				.and()
			.build();
	}

	/**
	 * 커스텀 주문을 생성하기 위한 빌더를 반환한다.
	 *
	 * <p>복잡한 주문 구성이 필요할 때 사용한다. 여러 OrderBooking을 추가할 수 있다.</p>
	 *
	 * <h4>사용 예시</h4>
	 * <pre>{@code
	 * // 단일 OrderBooking 주문
	 * OrderResult result = orderTestHelper.builder(member)
	 *     .addOrderBooking(schedule)
	 *         .setPendingBookingId(pendingBookingId)
	 *         .setDepartureScheduleStop(departureStop)
	 *         .setArrivalScheduleStop(arrivalStop)
	 *         .setTotalFare(BigDecimal.valueOf(10000)) // OrderBooking 운임 설정 (지정 안하면 자동 계산)
	 *         .addSeatsByCarType(CarType.FIRST_CLASS, 2, PassengerType.ADULT)
	 *         .and()
	 *     .build();
	 *
	 * // 여러 OrderBooking 주문 (왕복 등)
	 * OrderResult result = orderTestHelper.builder(member)
	 *     .addOrderBooking(schedule1)
	 *         .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT)
	 *         .and()
	 *     .addOrderBooking(schedule2)
	 *         .addSeat(seat, PassengerType.CHILD)
	 *         .and()
	 *     .build();
	 * }</pre>
	 */
	public OrderBuilder builder(Member member) {
		return new OrderBuilder(self, member);
	}

	public OrderResult persist(OrderBuilder builder) {
		builder.validateRequired();

		BigDecimal totalAmount = builder.calculateTotalAmount();
		Order order = orderRepository.save(Order.create(builder.member, totalAmount));

		List<OrderBooking> orderBookings = new ArrayList<>();
		List<OrderSeatBooking> orderSeatBookings = new ArrayList<>();

		for (OrderBookingBuilder bookingBuilder : builder.orderBookingBuilders) {
			bookingBuilder.setDefaultStops();

			OrderBooking orderBooking = orderBookingRepository.save(
				OrderBooking.create(
					bookingBuilder.pendingBookingId,
					order,
					bookingBuilder.trainScheduleResult.trainSchedule(),
					bookingBuilder.departureScheduleStop,
					bookingBuilder.arrivalScheduleStop,
					bookingBuilder.getTotalFare()
				)
			);
			orderBookings.add(orderBooking);

			List<OrderSeatBooking> seatBookings = saveOrderSeatBookings(orderBooking, bookingBuilder);
			orderSeatBookings.addAll(seatBookings);
		}

		return new OrderResult(order, orderBookings, orderSeatBookings);
	}

	private List<OrderSeatBooking> saveOrderSeatBookings(OrderBooking orderBooking, OrderBookingBuilder builder) {
		if (builder.seatWithPassengerTypes.isEmpty()) {
			return List.of();
		}

		Long departureStationId = builder.departureScheduleStop.getStation().getId();
		Long arrivalStationId = builder.arrivalScheduleStop.getStation().getId();

		List<OrderSeatBooking> toSave = builder.seatWithPassengerTypes.stream()
			.map(sp -> {
				BigDecimal fare = fareCalculator.calculateFare(
					departureStationId,
					arrivalStationId,
					sp.passengerType(),
					sp.seat().getTrainCar().getCarType()
				);
				return OrderSeatBooking.create(
					orderBooking,
					sp.seat().getId(),
					sp.passengerType(),
					fare
				);
			})
			.toList();

		return orderSeatBookingRepository.saveAll(toSave);
	}

	/**
	 * Order 생성용 Builder
	 */
	public class OrderBuilder {
		private final OrderTestHelper helper;
		private final Member member;
		private final List<OrderBookingBuilder> orderBookingBuilders = new ArrayList<>();

		public OrderBuilder(OrderTestHelper helper, Member member) {
			this.helper = helper;
			this.member = member;
		}

		/**
		 * OrderBooking을 추가한다.
		 *
		 * @param trainScheduleResult 열차 스케줄 및 정차역 정보
		 * @return OrderBookingBuilder
		 */
		public OrderBookingBuilder addOrderBooking(TrainScheduleResult trainScheduleResult) {
			OrderBookingBuilder builder = new OrderBookingBuilder(this, trainScheduleResult);
			orderBookingBuilders.add(builder);
			return builder;
		}

		public OrderResult build() {
			return helper.persist(this);
		}

		private void validateRequired() {
			if (member == null) {
				throw new IllegalArgumentException("member는 필수입니다.");
			}
			if (orderBookingBuilders.isEmpty()) {
				throw new IllegalArgumentException("최소 하나의 OrderBooking이 필요합니다.");
			}
		}

		private BigDecimal calculateTotalAmount() {
			return orderBookingBuilders.stream()
				.map(OrderBookingBuilder::getTotalFare)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		}
	}

	/**
	 * OrderBooking 생성용 Builder
	 */
	public class OrderBookingBuilder {
		private String pendingBookingId = UUID.randomUUID().toString();
		private final OrderBuilder parent;
		private final TrainScheduleResult trainScheduleResult;
		private final List<SeatWithPassengerType> seatWithPassengerTypes = new ArrayList<>();
		private ScheduleStop departureScheduleStop;
		private ScheduleStop arrivalScheduleStop;
		private BigDecimal totalFare;

		public OrderBookingBuilder(OrderBuilder parent, TrainScheduleResult trainScheduleResult) {
			this.parent = parent;
			this.trainScheduleResult = trainScheduleResult;
		}

		/**
		 * 예약 ID를 설정한다.
		 * <p>설정하지 않으면 UUID로 기본 예약 ID가 생성된다.</p>
		 */
		public OrderBookingBuilder setPendingBookingId(String pendingBookingId) {
			this.pendingBookingId = pendingBookingId;
			return this;
		}

		/**
		 * 출발역을 설정한다.
		 * <p>설정하지 않으면 스케줄의 첫 번째 정차역(출발역)이 사용된다.</p>
		 */
		public OrderBookingBuilder setDepartureScheduleStop(ScheduleStop departureScheduleStop) {
			this.departureScheduleStop = departureScheduleStop;
			return this;
		}

		/**
		 * 도착역을 설정한다.
		 * <p>설정하지 않으면 스케줄의 마지막 정차역(도착역)이 사용된다.</p>
		 */
		public OrderBookingBuilder setArrivalScheduleStop(ScheduleStop arrivalScheduleStop) {
			this.arrivalScheduleStop = arrivalScheduleStop;
			return this;
		}

		/**
		 * 이 OrderBooking의 운임을 수동으로 설정한다.
		 * <p>설정하지 않으면 좌석 정보를 기반으로 자동 계산된다.</p>
		 */
		public OrderBookingBuilder setTotalFare(BigDecimal totalFare) {
			this.totalFare = totalFare;
			return this;
		}

		/**
		 * 좌석을 직접 지정하여 추가한다.
		 */
		public OrderBookingBuilder addSeat(Seat seat, PassengerType passengerType) {
			validateSeat(seat, trainScheduleResult.trainSchedule().getTrain());
			seatWithPassengerTypes.add(new SeatWithPassengerType(seat, passengerType));
			return this;
		}

		/**
		 * 좌석 리스트를 한꺼번에 추가한다.
		 */
		public OrderBookingBuilder addSeats(List<Seat> seats, PassengerType passengerType) {
			seats.forEach(seat -> addSeat(seat, passengerType));
			return this;
		}

		/**
		 * 객차 유형과 개수로 좌석을 추가한다.
		 * <p>해당 열차에 예약 안된 좌석만 가져오므로 중복 예약 충돌을 방지한다.</p>
		 */
		public OrderBookingBuilder addSeatsByCarType(CarType carType, int count, PassengerType passengerType) {
			List<Seat> seats = trainTestHelper.getAvailableSeats(trainScheduleResult.trainSchedule(), carType, count);
			seats.forEach(seat -> addSeat(seat, passengerType));
			return this;
		}

		public OrderBuilder and() {
			return parent;
		}

		private BigDecimal getTotalFare() {
			if (totalFare != null) {
				return totalFare;
			}
			return calculateTotalFare();
		}

		private void setDefaultStops() {
			if (departureScheduleStop == null) {
				departureScheduleStop = getFirstStop(trainScheduleResult);
			}
			if (arrivalScheduleStop == null) {
				arrivalScheduleStop = getLastStop(trainScheduleResult);
			}
		}

		private BigDecimal calculateTotalFare() {
			if (seatWithPassengerTypes.isEmpty()) {
				return BigDecimal.ZERO;
			}

			setDefaultStops();

			// 각 CarType별로 운임 계산 후 합산
			return seatWithPassengerTypes.stream()
				.map(sp -> fareCalculator.calculateFare(
					departureScheduleStop.getStation().getId(),
					arrivalScheduleStop.getStation().getId(),
					sp.passengerType,
					sp.seat().getTrainCar().getCarType()
				))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		}

		private void validateSeat(Seat seat, Train train) {
			if (!seat.getTrainCar().getTrain().getId().equals(train.getId())) {
				throw new IllegalArgumentException("열차에 해당하지 않는 좌석입니다.");
			}
		}

		private ScheduleStop getFirstStop(TrainScheduleResult trainScheduleResult) {
			List<ScheduleStop> stops = trainScheduleResult.scheduleStops();
			if (stops.isEmpty()) {
				throw new IllegalArgumentException("출발역을 찾을 수 없습니다.");
			}
			return stops.get(0);
		}

		private ScheduleStop getLastStop(TrainScheduleResult trainScheduleResult) {
			List<ScheduleStop> stops = trainScheduleResult.scheduleStops();
			if (stops.isEmpty()) {
				throw new IllegalArgumentException("도착역을 찾을 수 없습니다.");
			}
			return stops.get(stops.size() - 1);
		}

		private record SeatWithPassengerType(Seat seat, PassengerType passengerType) {}
	}
}
