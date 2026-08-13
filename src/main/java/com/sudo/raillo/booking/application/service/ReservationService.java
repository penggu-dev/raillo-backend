package com.sudo.raillo.booking.application.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateCommand;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.booking.infrastructure.ReservationSeatRepository;
import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.train.domain.Seat;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 예약 서비스
 *
 * <p>Reservation / ReservationSeat 의 영속화만 책임진다.
 * 좌석 점유({@code seat_occupancy})는 {@link SeatOccupancyService}가 담당하며,
 * 두 서비스의 조율은 Facade가 한다 (Service → Service 호출 금지).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

	private final ReservationRepository reservationRepository;
	private final ReservationSeatRepository reservationSeatRepository;
	private final FareCalculator fareCalculator;

	/**
	 * 점유중(HELD) 상태의 예약과 예약 좌석을 생성한다.
	 *
	 * <p>좌석 점유는 호출하는 Facade가 {@link SeatOccupancyService#hold} 로 이어서 수행해야 한다.</p>
	 */
	public Reservation createHeld(ReservationCreateCommand command) {
		Reservation reservation = reservationRepository.save(Reservation.create(
			command.reservationCode(),
			command.memberNo(),
			command.trainSchedule(),
			command.departureStop(),
			command.arrivalStop(),
			command.totalFare(),
			command.expiresAt()
		));

		reservationSeatRepository.saveAll(createReservationSeats(reservation, command));

		log.info("[예약 생성] reservationId={}, reservationCode={}, memberNo={}, seatCount={}",
			reservation.getId(), reservation.getReservationCode(), command.memberNo(), command.seats().size());

		return reservation;
	}

	@Transactional(readOnly = true)
	public List<Reservation> getByReservationCodes(List<String> reservationCodes) {
		if (reservationCodes.isEmpty()) {
			return List.of();
		}
		return reservationRepository.findAllByReservationCodeIn(reservationCodes);
	}

	@Transactional(readOnly = true)
	public List<Reservation> getActiveByMemberNo(String memberNo, LocalDateTime now) {
		return reservationRepository.findActiveByMemberNo(memberNo, ReservationStatus.HELD, now);
	}

	/**
	 * 예약을 해제 상태로 전이시키고 해제 대상 목록을 반환한다.
	 *
	 * <p>좌석 점유 행 삭제는 호출하는 Facade가
	 * {@link SeatOccupancyService#releaseByReservationIds} 로 이어서 수행해야 한다.</p>
	 *
	 * @return 해제된 예약 목록 (이미 해제·확정된 건은 제외)
	 */
	public List<Reservation> release(List<String> reservationCodes) {
		List<Reservation> reservations = getByReservationCodes(reservationCodes).stream()
			.filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
			.toList();

		reservations.forEach(Reservation::release);

		log.info("[예약 해제] reservationCodes={}, releasedCount={}", reservationCodes, reservations.size());
		return reservations;
	}

	private List<ReservationSeat> createReservationSeats(Reservation reservation, ReservationCreateCommand command) {
		Long departureStationId = command.departureStop().getStation().getId();
		Long arrivalStationId = command.arrivalStop().getStation().getId();

		return IntStream.range(0, command.seats().size())
			.mapToObj(index -> {
				Seat seat = command.seats().get(index);
				PassengerType passengerType = command.passengerTypes().get(index);
				BigDecimal fare = fareCalculator.calculateFare(
					departureStationId,
					arrivalStationId,
					passengerType,
					seat.getTrainCar().getCarType()
				);
				return ReservationSeat.create(reservation, seat, passengerType, fare);
			})
			.toList();
	}
}
