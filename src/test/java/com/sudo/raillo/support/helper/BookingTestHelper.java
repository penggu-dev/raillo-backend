package com.sudo.raillo.support.helper;

import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.train.domain.Train;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Transactional
public class BookingTestHelper {

	private final TrainTestHelper trainTestHelper;
	private final BookingRepository bookingRepository;
	private final SeatBookingRepository seatBookingRepository;
	private final TicketRepository  ticketRepository;

	@Lazy
	@Autowired
	private BookingTestHelper self;

	/**
	 * 기본 예약 생성 메서드
	 *
	 * <p>출발역에서 도착역까지 성인 1명, 일반석 1좌석으로 예약을 생성한다.</p>
	 *
	 * @param member 예약할 회원
	 * @param trainScheduleResult 열차 스케줄 및 정차역 정보
	 * @return 생성된 예약과 좌석 예약 정보
	 */
	public BookingResult createDefault(Member member, TrainScheduleResult trainScheduleResult) {
		return builder(member, trainScheduleResult)
			.addSeatsByCarType(CarType.STANDARD, 1, PassengerType.ADULT)
			.build();
	}

	/**
	 * 커스텀 예약을 생성하기 위한 빌더를 반환한다.
	 *
	 * <p>복잡한 예약 구성이 필요할 때 사용한다. 출발역, 도착역, 좌석, 승객 유형 등을 자유롭게 설정할 수 있다.</p>
	 *
	 * <h4>사용 예시</h4>
	 * <pre>{@code
	 * // 중간역 구간 예약 + 특등석 2좌석
	 * BookingWithSeats result = bookingTestHelper.builder(member, schedule)
	 *     .setDepartureScheduleStop(departureScheduleStop) // 출발 scheduleStop
	 *     .setArrivalScheduleStop(arrivalScheduleStop) // 도착 scheduleStop
	 *     .addSeatsByCarType(CarType.STANDARD, 2, PassengerType.ADULT) // 일반석,어른 2개 좌석 예약
	 *     .build();
	 *
	 * // 성인 + 어린이 혼합 예약
	 * BookingWithSeats result = bookingTestHelper.builder(member, schedule)
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

		Booking booking = bookingRepository.save(
			Booking.create(
				builder.member,
				builder.trainScheduleResult.trainSchedule(),
				builder.departureScheduleStop,
				builder.arrivalScheduleStop
			)
		);

		List<SeatBooking> savedSeatBookings = saveSeatBookings(booking, builder);
		List<Ticket> savedTickets = savedTickets(booking, builder);

		return new BookingResult(booking, savedSeatBookings, savedTickets);
	}

	private List<SeatBooking> saveSeatBookings(Booking booking, BookingBuilder builder) {
		if (builder.seatBookings.isEmpty()) {
			return List.of();
		}

		List<SeatBooking> toSave = builder.seatBookings.stream()
			.map(sb -> SeatBooking.create(
				booking.getTrainSchedule(),
				sb.getSeat(),
				booking,
				sb.getPassengerType()
			))
			.toList();

		return seatBookingRepository.saveAll(toSave);
	}

	private List<Ticket> savedTickets(Booking booking, BookingBuilder builder) {
		if (builder.seatBookings.isEmpty()) {
			return List.of();
		}

		List<Ticket> tickets = builder.seatBookings.stream()
			.map(sb -> Ticket.builder()
				.booking(booking)
				.seat(sb.getSeat())
				.passengerType(sb.getPassengerType())
				.ticketStatus(TicketStatus.ISSUED)
				.build()
			).toList();

		return ticketRepository.saveAll(tickets);
	}

	/**
	 * Booking 생성용 Builder
	 */
	public class BookingBuilder {
		private final BookingTestHelper helper;
		private final List<SeatBooking> seatBookings = new ArrayList<>();
		private final Member member;
		private final TrainScheduleResult trainScheduleResult;
		private ScheduleStop departureScheduleStop;
		private ScheduleStop arrivalScheduleStop;

		public BookingBuilder(BookingTestHelper helper, Member member, TrainScheduleResult trainScheduleResult) {
			this.helper = helper;
			this.trainScheduleResult = trainScheduleResult;
			this.member = member;
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
			SeatBooking seatBooking = SeatBooking.create(null, seat, null, passengerType);
			seatBookings.add(seatBooking);
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
			if (member == null) {
				throw new IllegalArgumentException("member는 필수입니다.");
			}
			if (trainScheduleResult == null) {
				throw new IllegalArgumentException("schedule은 필수입니다.");
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
	}
}
