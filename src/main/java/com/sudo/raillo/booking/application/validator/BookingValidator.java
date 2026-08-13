package com.sudo.raillo.booking.application.validator;

import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyQueryRepository;
import com.sudo.raillo.member.domain.Member;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingValidator {

	private static final long BOOKING_CLOSE_MINUTES_BEFORE_DEPARTURE = 5L;

	private final SeatOccupancyQueryRepository seatOccupancyQueryRepository;

	/**
	 * 출발지, 도착지 순서 검증
	 */
	public void validateStopSequence(ScheduleStop departureStop, ScheduleStop arrivalStop) {
		if (departureStop.getStopOrder() > arrivalStop.getStopOrder()) {
			throw new BusinessException(BookingError.TRAIN_NOT_OPERATIONAL);
		}
	}

	/**
	 * 출발역, 도착역이 같은 스케줄을 가지고 있는지 검증
	 * */
	public void validateSameSchedule(ScheduleStop departureStop, ScheduleStop arrivalStop) {
		if (!departureStop.getTrainSchedule().getId().equals(arrivalStop.getTrainSchedule().getId())) {
			throw new BusinessException(TrainError.INVALID_ROUTE);
		}
	}

	/**
	 * 열차 스케줄 운행 여부 확인
	 * */
	public void validateTrainOperating(TrainSchedule trainSchedule) {
		if (trainSchedule.getOperationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainError.TRAIN_OPERATION_CANCELLED);
		}
	}

	public void validateDepartureTimeNotPassed(LocalDateTime departureDateTime, LocalDateTime now) {
		LocalDateTime bookingClosedAt = departureDateTime.minusMinutes(BOOKING_CLOSE_MINUTES_BEFORE_DEPARTURE);

		if (!now.isBefore(bookingClosedAt)) {
			throw new BusinessException(TrainError.DEPARTURE_TIME_PASSED);
		}
	}

	/**
	 * 요청된 승객 수와 선택한 좌석 수의 일치 여부를 검증
	 * */
	public void validatePassengerSeatCount(List<PassengerType> passengerTypes, List<Long> seatIds) {
		// 요청 승객 수와 선택한 좌석 수를 비교하여 좌석 수가 승객 수보다 많으면 오류 발생
		if (passengerTypes.size() != seatIds.size()) {
			throw new BusinessException(BookingError.BOOKING_CREATE_SEATS_INVALID);
		}
	}

	/**
	 * 요청한 예약이 모두 조회되었는지 검증
	 *
	 * <p>없는 예약과 만료된 예약을 같은 오류로 다룬다. Redis TTL 시절 키가 사라진 것과
	 * 만료된 것을 구분할 수 없었던 동작을 그대로 유지한다.</p>
	 */
	public void validateAllReservationsExist(List<String> reservationCodes, List<Reservation> reservations) {
		if (reservations.size() != reservationCodes.size()) {
			log.warn("[예약 조회 실패] 요청={}, 조회={} - 만료되었거나 이미 사용됨",
				reservationCodes.size(), reservations.size());
			throw new BusinessException(BookingError.RESERVATION_EXPIRED);
		}
	}

	/**
	 * 예약 접근 권한 확인
	 */
	public void validateReservationOwner(Reservation reservation, String memberNo) {
		if (!reservation.getMemberNo().equals(memberNo)) {
			log.error("[예약 소유자 불일치] reservationMemberNo={}, requestMemberNo={}",
				reservation.getMemberNo(), memberNo);
			throw new BusinessException(BookingError.PENDING_BOOKING_ACCESS_DENIED);
		}
	}

	/**
	 * 결제 가능한 상태인지 검증 (점유중이면서 만료되지 않음)
	 */
	public void validateReservationPayable(Reservation reservation, LocalDateTime now) {
		if (!reservation.isPayable(now)) {
			log.warn("[결제 불가 예약] reservationCode={}, status={}, expiresAt={}",
				reservation.getReservationCode(), reservation.getStatus(), reservation.getExpiresAt());
			throw new BusinessException(BookingError.RESERVATION_EXPIRED);
		}
	}

	/**
	 * 좌석 검증
	 * <p>1. 좌석 존재 여부 검증
	 * <p>2. 동일 객차 타입 검증
	 */
	public CarType validateSeatIdsAndGetSingleCarType(List<CarType> carTypes) {
		if (carTypes.isEmpty()) {
			log.warn("[좌석 조회 실패] 요청한 좌석 ID에 해당하는 좌석이 없음");
			throw new BusinessException(TrainError.SEAT_NOT_FOUND);
		}

		if (carTypes.size() != 1) {
			log.warn("[객차 타입 불일치] 서로 다른 객차 타입이 섞여 있음: carTypes={}", carTypes);
			throw new BusinessException(BookingError.INVALID_CAR_TYPE);
		}
		return carTypes.get(0);
	}

	/**
	 * 승차권 소유자 검증
	 */
	public void validateTicketOwner(Ticket ticket, Member member) {
		if (!ticket.getBooking().getMember().getId().equals(member.getId())) {
			throw new BusinessException(BookingError.TICKET_ACCESS_DENIED);
		}
	}

	/**
	 * 요청 구간에 이미 점유된 좌석이 있는지 검증한다.
	 *
	 * <p>예약(HELD)과 확정 예매(CONFIRMED)가 {@code seat_occupancy} 한 테이블에 있으므로
	 * 한 번의 조회로 두 종류의 충돌을 모두 판정한다.</p>
	 *
	 * <p>최종 충돌 방어는 {@code uk_seat_occupancy_section} 유니크 제약이 하며,
	 * 이 검증은 빠른 실패를 위한 보조 수단이다.</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param departureStop 출발 정류장
	 * @param arrivalStop 도착 정류장
	 * @param seatIds 좌석 ID 목록
	 */
	public void validateSeatConflicts(
		Long trainScheduleId,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Long> seatIds
	) {
		Set<Long> occupiedSeatIds = seatOccupancyQueryRepository.findOccupiedSeatIdsAmong(
			trainScheduleId,
			seatIds,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder(),
			LocalDateTime.now()
		);

		if (!occupiedSeatIds.isEmpty()) {
			log.warn("[좌석 점유 충돌] trainScheduleId={}, occupiedSeatIds={}, request=[{}-{}]",
				trainScheduleId, occupiedSeatIds, departureStop.getStopOrder(), arrivalStop.getStopOrder());
			throw new BusinessException(BookingError.SEAT_ALREADY_OCCUPIED);
		}
	}

}
