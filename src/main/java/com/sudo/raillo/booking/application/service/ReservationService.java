package com.sudo.raillo.booking.application.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateCommand;
import com.sudo.raillo.booking.application.dto.response.ReservationDetailResponse;
import com.sudo.raillo.booking.application.mapper.ReservationMapper;
import com.sudo.raillo.booking.application.validator.BookingValidator;
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
	private final ReservationMapper reservationMapper;
	private final BookingValidator bookingValidator;
	private final FareCalculator fareCalculator;

	/** 예약 기본 유효 기간 (Redis TTL 대체) */
	@Value("${reservation.ttl}")
	private Duration reservationTtl;

	/**
	 * 점유중(HELD) 상태의 예약과 예약 좌석을 생성한다.
	 *
	 * <p>좌석 점유는 호출하는 Facade가 {@link SeatOccupancyService#hold} 로 이어서 수행해야 한다.</p>
	 */
	public Reservation createHeld(ReservationCreateCommand command) {
		List<BigDecimal> seatFares = calculateSeatFares(command);
		BigDecimal totalFare = seatFares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

		Reservation reservation = reservationRepository.save(Reservation.create(
			command.reservationCode(),
			command.memberNo(),
			command.trainSchedule(),
			command.departureStop(),
			command.arrivalStop(),
			totalFare,
			command.expiresAt()
		));

		reservationSeatRepository.saveAll(createReservationSeats(reservation, command, seatFares));

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

	/**
	 * 결제 가능한 예약 조회 (존재·소유자·만료 검증 포함)
	 */
	@Transactional(readOnly = true)
	public List<Reservation> getPayableByCodes(List<String> reservationCodes, String memberNo) {
		return validatePayable(getByReservationCodes(reservationCodes), reservationCodes, memberNo);
	}

	/**
	 * 결제 승인용 예약 조회 — 예약 행을 잠근 뒤 검증한다.
	 *
	 * <p>만료 정리·중복 결제와 직렬화하기 위한 보조 장치다. 실제 안전장치는
	 * 좌석 점유 확정 시의 affected rows 검사다.</p>
	 */
	public List<Reservation> getPayableByCodesForUpdate(List<String> reservationCodes, String memberNo) {
		List<Reservation> reservations = reservationCodes.isEmpty()
			? List.of()
			: reservationRepository.findAllByReservationCodeInForUpdate(reservationCodes);
		return validatePayable(reservations, reservationCodes, memberNo);
	}

	/**
	 * 회원의 유효한 예약 목록 조회
	 */
	@Transactional(readOnly = true)
	public List<ReservationDetailResponse> getReservationDetails(String memberNo) {
		List<Reservation> reservations = reservationRepository.findActiveByMemberNo(
			memberNo, ReservationStatus.HELD, LocalDateTime.now());

		if (reservations.isEmpty()) {
			return List.of();
		}

		Map<Long, List<ReservationSeat>> seatsByReservationId = reservationSeatRepository
			.findAllByReservationIdInWithSeat(reservations.stream().map(Reservation::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(reservationSeat -> reservationSeat.getReservation().getId()));

		return reservations.stream()
			.map(reservation -> reservationMapper.convertToReservationDetail(
				reservation, seatsByReservationId.getOrDefault(reservation.getId(), List.of())))
			.toList();
	}

	/**
	 * 예약 좌석 수 조회 (좌석 점유 확정 시 기대 행 수 계산용)
	 */
	@Transactional(readOnly = true)
	public int countSeats(Long reservationId) {
		return reservationSeatRepository.countByReservationId(reservationId);
	}

	/**
	 * 소유자 검증만 거친 예약 조회 (삭제용 — 만료된 예약도 삭제할 수 있어야 한다)
	 */
	@Transactional(readOnly = true)
	public List<Reservation> getOwnedByCodes(List<String> reservationCodes, String memberNo) {
		List<Reservation> reservations = getByReservationCodes(reservationCodes);

		bookingValidator.validateAllReservationsExist(reservationCodes, reservations);
		reservations.forEach(reservation -> bookingValidator.validateReservationOwner(reservation, memberNo));

		return reservations;
	}

	/**
	 * 예약을 해제 상태로 전이시키고 해제 대상 목록을 반환한다.
	 *
	 * <p>좌석 점유 행 삭제는 호출하는 Facade가
	 * {@link SeatOccupancyService#releaseByReservationIds} 로 이어서 수행해야 한다.</p>
	 *
	 * @return 해제된 예약 목록 (이미 해제·확정된 건은 제외)
	 */
	public List<Reservation> release(List<Reservation> reservations) {
		List<Reservation> heldReservations = reservations.stream()
			.filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
			.toList();

		heldReservations.forEach(Reservation::release);

		log.info("[예약 해제] requested={}, released={}", reservations.size(), heldReservations.size());
		return heldReservations;
	}

	/**
	 * 예약 만료 시각까지의 유효 기간 계산
	 *
	 * <p>출발까지 남은 시간과 기본 TTL 중 짧은 값을 반환한다.</p>
	 */
	public Duration calculateReservationTtl(LocalDateTime departureDateTime, LocalDateTime now) {
		Duration remainingUntilDeparture = Duration.between(now, departureDateTime);

		return remainingUntilDeparture.compareTo(reservationTtl) < 0
			? remainingUntilDeparture
			: reservationTtl;
	}

	/**
	 * 요청한 코드 순서를 유지하면서 존재·소유자·만료를 검증한다.
	 */
	private List<Reservation> validatePayable(
		List<Reservation> reservations,
		List<String> reservationCodes,
		String memberNo
	) {
		bookingValidator.validateAllReservationsExist(reservationCodes, reservations);

		LocalDateTime now = LocalDateTime.now();
		reservations.forEach(reservation -> {
			bookingValidator.validateReservationOwner(reservation, memberNo);
			bookingValidator.validateReservationPayable(reservation, now);
		});

		Map<String, Reservation> byCode = reservations.stream()
			.collect(Collectors.toMap(Reservation::getReservationCode, Function.identity()));

		return reservationCodes.stream()
			.map(byCode::get)
			.toList();
	}

	/**
	 * 좌석별 운임 계산
	 *
	 * <p>예약 총액은 이 값들의 합이다. 주문·결제 단계에서 재계산하지 않으므로
	 * 예약 화면에 표시된 금액과 결제 금액이 항상 일치한다.</p>
	 */
	private List<BigDecimal> calculateSeatFares(ReservationCreateCommand command) {
		Long departureStationId = command.departureStop().getStation().getId();
		Long arrivalStationId = command.arrivalStop().getStation().getId();

		return IntStream.range(0, command.seats().size())
			.mapToObj(index -> fareCalculator.calculateFare(
				departureStationId,
				arrivalStationId,
				command.passengerTypes().get(index),
				command.seats().get(index).getTrainCar().getCarType()
			))
			.toList();
	}

	private List<ReservationSeat> createReservationSeats(
		Reservation reservation,
		ReservationCreateCommand command,
		List<BigDecimal> seatFares
	) {
		return IntStream.range(0, command.seats().size())
			.mapToObj(index -> ReservationSeat.create(
				reservation,
				command.seats().get(index),
				command.passengerTypes().get(index),
				seatFares.get(index)
			))
			.toList();
	}
}
