package com.sudo.raillo.support.helper;

import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.support.fixture.OrderFixture;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class BookingTestHelper {

	private final TrainTestHelper trainTestHelper;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final BookingRepository bookingRepository;
	private final OrderRepository orderRepository;
	private final SeatBookingRepository seatBookingRepository;
	private final SeatRepository seatRepository;
	private final TicketRepository ticketRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final FareCalculator fareCalculator;

	@Lazy
	@Autowired
	private BookingTestHelper self;

	/**
	 * 기본 예매 생성 메서드
	 *
	 * <p>출발역에서 도착역까지 성인 1명, 일반석 1좌석으로 예매를 생성한다.</p>
	 *
	 * @param member 예매할 회원
	 * @param trainScheduleResult 열차 스케줄 및 정차역 정보
	 * @return 생성된 예매과 예매 좌석 정보
	 */
	public BookingResult createDefault(Member member, TrainScheduleResult trainScheduleResult) {
		return builder(member, trainScheduleResult)
			.addSeatsByCarType(CarType.STANDARD, 1, PassengerType.ADULT)
			.build();
	}

	/**
	 * OrderBooking 정보를 기반으로 예매를 생성한다.
	 *
	 * <p>전달받은 {@link OrderBooking}에 연관된 회원과 열차 스케줄 정보를 사용하여 예매를 구성한다.</p>
	 */
	public BookingResult createByOrderBooking(OrderBooking orderBooking) {
		TrainScheduleResult trainScheduleResult = new TrainScheduleResult(
			orderBooking.getTrainSchedule(),
			scheduleStopRepository.findByTrainScheduleIdOrderByStopOrderAsc(orderBooking.getTrainSchedule().getId())
		);
		BookingBuilder builder = builder(orderBooking.getOrder().getMember(), trainScheduleResult);
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository.findByOrderBookingId(orderBooking.getId());

		orderSeatBookings.forEach(osb -> {
			Seat seat = seatRepository.findById(osb.getSeatId())
				.orElseThrow(() -> new BusinessException(TrainErrorCode.SEAT_NOT_FOUND));
			builder.addSeat(seat, osb.getPassengerType());
		});
		return builder.build();
	}

	/**
	 * 커스텀 예매를 생성하기 위한 빌더를 반환한다.
	 *
	 * <p>복잡한 예매 구성이 필요할 때 사용한다. 출발역, 도착역, 좌석, 승객 유형 등을 자유롭게 설정할 수 있다.</p>
	 * <p>예매 기준으로 Order가 자동으로 생성된다.</p>
	 * <h4>사용 예시</h4>
	 * <pre>{@code
	 * // 중간역 구간 예매 + 특등석 2좌석
	 * BookingResult result = bookingTestHelper.builder(member, trainScheduleResult)
	 *     .setDepartureScheduleStop(departureScheduleStop) // 출발 scheduleStop
	 *     .setArrivalScheduleStop(arrivalScheduleStop) // 도착 scheduleStop
	 *     .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT) // 일반석,어른 2개 좌석
	 *     .build();
	 *
	 * // 성인 + 어린이 혼합 예매
	 * BookingResult result = bookingTestHelper.builder(member, trainScheduleResult)
	 *     .addSeat(seat1, PassengerType.ADULT)
	 *     .addSeat(seat2, PassengerType.CHILD)
	 *     .build();
	 * }</pre>
	 */
	public BookingBuilder builder(Member member, TrainScheduleResult trainScheduleResult) {
		return new BookingBuilder(self, member, trainScheduleResult);
	}

	public BookingResult persist(BookingBuilder builder) {
		builder.validateRequired();
		builder.setDefaultStops();

		builder.setOrder();
		orderRepository.save(builder.order);

		Booking booking = bookingRepository.save(
			Booking.create(
				builder.member,
				builder.order,
				builder.trainScheduleResult.trainSchedule(),
				builder.departureScheduleStop,
				builder.arrivalScheduleStop
			)
		);

		List<SeatBooking> savedSeatBookings = saveSeatBookings(booking, builder);
		List<Ticket> savedTickets = builder.createTickets
			? savedTickets(booking, builder)
			: List.of();

		return new BookingResult(booking, savedSeatBookings, savedTickets);
	}

	private List<SeatBooking> saveSeatBookings(Booking booking, BookingBuilder builder) {
		if (builder.seatWithPassengerTypes.isEmpty()) {
			return List.of();
		}

		List<SeatBooking> toSave = builder.seatWithPassengerTypes.stream()
			.map(sp -> SeatBooking.create(
				booking,
				sp.seat,
				sp.passengerType
			))
			.toList();

		return seatBookingRepository.saveAll(toSave);
	}

	private List<Ticket> savedTickets(Booking booking, BookingBuilder builder) {
		if (builder.seatWithPassengerTypes.isEmpty()) {
			return List.of();
		}

		Long departureStationId = builder.departureScheduleStop.getStation().getId();
		Long arrivalStationId = builder.arrivalScheduleStop.getStation().getId();

		List<Ticket> tickets = builder.seatWithPassengerTypes.stream()
			.map(sp -> {
				BigDecimal fare = fareCalculator.calculateFare(
					departureStationId,
					arrivalStationId,
					sp.passengerType,
					sp.seat.getTrainCar().getCarType()
				);
				return Ticket.builder()
					.booking(booking)
					.seat(sp.seat)
					.passengerType(sp.passengerType)
					.fare(fare)
					.ticketStatus(TicketStatus.ISSUED)
					.build();
			}).toList();

		return ticketRepository.saveAll(tickets);
	}

	/**
	 * Booking 생성용 Builder
	 */
	public class BookingBuilder {
		private final BookingTestHelper helper;
		private final List<SeatWithPassengerType> seatWithPassengerTypes = new ArrayList<>();
		private final Member member;
		private final TrainScheduleResult trainScheduleResult;
		private Order order;
		private ScheduleStop departureScheduleStop;
		private ScheduleStop arrivalScheduleStop;
		private boolean createTickets = true;

		public BookingBuilder(BookingTestHelper helper, Member member, TrainScheduleResult trainScheduleResult) {
			this.helper = helper;
			this.trainScheduleResult = trainScheduleResult;
			this.member = member;
		}

		/**
		 * 승차권 생성을 건너뛴다.
		 * <p>TicketService 테스트처럼 Ticket을 직접 생성 테스트해야 하는 경우 사용한다.</p>
		 */
		public BookingBuilder withoutTickets() {
			this.createTickets = false;
			return this;
		}

		/**
		 * 출발역을 역 이름으로 설정한다.
		 * <p>설정하지 않으면 스케줄의 첫 번째 정차역(출발역)이 사용된다.</p>
		 */
		public BookingBuilder setDepartureScheduleStop(ScheduleStop departureScheduleStop) {
			this.departureScheduleStop = departureScheduleStop;
			return this;
		}

		/**
		 * 도착역을 역 이름으로 설정한다.
		 * <p>설정하지 않으면 스케줄의 마지막 정차역(도착역)이 사용된다.</p>
		 */
		public BookingBuilder setArrivalScheduleStop(ScheduleStop arrivalScheduleStop) {
			this.arrivalScheduleStop = arrivalScheduleStop;
			return this;
		}

		/**
		 * 좌석을 직접 지정하여 추가한다.
		 */
		public BookingBuilder addSeat(Seat seat, PassengerType passengerType) {
			validateSeat(seat, trainScheduleResult.trainSchedule().getTrain());
			seatWithPassengerTypes.add(new SeatWithPassengerType(seat, passengerType));
			return this;
		}

		/**
		 * 좌석 리스트를 한꺼번에 추가한다.
		 */
		public BookingBuilder addSeats(List<Seat> seats, PassengerType passengerType) {
			seats.forEach(seat -> addSeat(seat, passengerType));
			return this;
		}

		/**
		 * 객차 유형과 개수로 좌석을 추가한다.
		 * <p>해당 열차에 예약 안된 좌석만 가져오므로 중복 예약 충돌을 방지한다.</p>
		 */
		public BookingBuilder addSeatsByCarType(CarType carType, int count, PassengerType passengerType) {
			List<Seat> seats = trainTestHelper.getAvailableSeats(trainScheduleResult.trainSchedule(), carType, count);
			seats.forEach(seat -> addSeat(seat, passengerType));
			return this;
		}

		public BookingResult build() {
			return helper.persist(this);
		}

		private void validateRequired() {
			if (seatWithPassengerTypes.isEmpty()) {
				throw new IllegalArgumentException("좌석 정보가 없으면 Order를 생성할 수 없습니다.");
			}

			if (member == null) {
				throw new IllegalArgumentException("member는 필수입니다.");
			}
			if (trainScheduleResult == null) {
				throw new IllegalArgumentException("schedule은 필수입니다.");
			}

			if (departureScheduleStop != null) {
				if (!Objects.equals(departureScheduleStop.getTrainSchedule().getId(), trainScheduleResult.trainSchedule().getId())) {
					throw new IllegalArgumentException("출발역 스케줄이 열차 스케줄과 일치하지 않습니다.");
				}
			}

			if (arrivalScheduleStop != null) {
				if (!Objects.equals(arrivalScheduleStop.getTrainSchedule().getId(), trainScheduleResult.trainSchedule().getId())) {
					throw new IllegalArgumentException("도착역 스케줄이 열차 스케줄과 일치하지 않습니다.");
				}
			}
		}

		private void setDefaultStops() {
			if (departureScheduleStop == null) {
				departureScheduleStop = getFirstStop(trainScheduleResult);
			}
			if (arrivalScheduleStop == null) {
				arrivalScheduleStop = getLastStop(trainScheduleResult);
			}
		}

		private void setOrder() {
			if (order == null) {
				BigDecimal totalAmount = seatWithPassengerTypes.stream()
					.map(sp -> fareCalculator.calculateFare(
						departureScheduleStop.getStation().getId(),
						arrivalScheduleStop.getStation().getId(),
						sp.passengerType,
						sp.seat().getTrainCar().getCarType()
					))
					.reduce(BigDecimal.ZERO, BigDecimal::add);

				order = OrderFixture.builder()
					.withMember(member)
					.withTotalAmount(totalAmount)
					.build();
			}
		}

		private void validateSeat(Seat seat, Train train) {
			if (!Objects.equals(seat.getTrainCar().getTrain().getId(), train.getId())) {
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
