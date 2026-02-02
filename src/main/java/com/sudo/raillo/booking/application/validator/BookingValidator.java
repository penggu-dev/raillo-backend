package com.sudo.raillo.booking.application.validator;

import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.redis.util.SeatHoldKeyGenerator;
import com.sudo.raillo.member.domain.Member;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingValidator {

	private final SeatHoldKeyGenerator seatHoldKeyGenerator;
	private final ScheduleStopRepository scheduleStopRepository;
	private final SeatBookingRepository seatBookingRepository;

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
			throw new BusinessException(TrainErrorCode.INVALID_ROUTE);
		}
	}

	/**
	 * 열차 스케줄 운행 여부 확인
	 * */
	public void validateTrainOperating(TrainSchedule trainSchedule) {
		if (trainSchedule.getOperationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainErrorCode.TRAIN_OPERATION_CANCELLED);
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
	 * 여러 개의 예약 접근 권한 확인
	 * @param pendingBookings 예약 리스트
	 * @param memberNo 회원 번호
	 */
	public void validatePendingBookingOwner(List<PendingBooking> pendingBookings, String memberNo) {
		pendingBookings.forEach(pendingBooking ->
			validatePendingBookingOwner(pendingBooking, memberNo));
	}

	/**
	 * 예약 접근 권한 확인
	 * @param pendingBooking 단일 예약
	 * @param memberNo 회원 번호
	 */
	public void validatePendingBookingOwner(PendingBooking pendingBooking, String memberNo) {
		if (!pendingBooking.getMemberNo().equals(memberNo)) {
			log.error("[예약 소유자 불일치] pendingBookingMemberNo={}, requestMemberNo={}",
				pendingBooking.getMemberNo(), memberNo);
			throw new BusinessException(BookingError.PENDING_BOOKING_ACCESS_DENIED);
		}
	}

	/**
	 * 예약 존재 여부 검증
	 */
	public void validateAllPendingBookingsExist(List<String> pendingBookingIds,
		Map<String, PendingBooking> bookingsById) {
		List<String> notFoundIds = pendingBookingIds.stream()
			.filter(id -> !bookingsById.containsKey(id))
			.toList();

		if (!notFoundIds.isEmpty()) {
			log.warn("[예약 찾지 못함] pendingBookingIds={} - TTL 만료 또는 이미 사용됨", notFoundIds);
			throw new BusinessException(BookingError.PENDING_BOOKING_NOT_FOUND);
		}
	}

	/**
	 * 좌석 검증
	 * 1. 좌석 존재 여부 검증
	 * 2. 동일 객차 타입 검증
	 */
	public CarType validateSeatIdsAndGetSingleCarType(List<CarType> carTypes) {
		if (carTypes.isEmpty()) {
			log.warn("[좌석 조회 실패] 요청한 좌석 ID에 해당하는 좌석이 없음");
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
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
	 // * 결제 준비 시 좌석 충돌 검증
	 * - Redis Hold 구간과 DB SeatBooking 구간 비교
	 * @param pendingBookings 결제할 PendingBooking 목록
	 */
	public void validateSeatConflicts(List<PendingBooking> pendingBookings) {
		for (PendingBooking pendingBooking : pendingBookings) {
			Long trainScheduleId = pendingBooking.getTrainScheduleId();
			List<Long> seatIds = getSeatIds(pendingBooking);

			// 1. DB에서 기존 예약 조회
			List<SeatBooking> existingSeatBookings = seatBookingRepository.findByTrainScheduleIdAndSeatIds(
				trainScheduleId, seatIds
			);

			// 기존 예매가 없으면 검증 없이 조기 반환
			if (existingSeatBookings.isEmpty()) {
				continue;
			}

			// 2. PendingBooking에서 구간 계산
			List<String> pendingSections = calculateSections(pendingBooking);

			// 3. 기존 SeatBooking과 구간 충돌 검증
			for (SeatBooking seatBooking : existingSeatBookings) {
				validateConflictWithSeatBooking(pendingSections, seatBooking, pendingBooking.getId());
			}
		}
	}

	/**
	 * PendingBooking 구간과 기존 SeatBooking 구간 충돌 검증
	 *
	 * @param pendingSections 예약하려는 구간 (예: ["0-1", "1-2"])
	 * @param seatBooking 기존 예매된 좌석
	 * @param pendingBookingId 로깅용 PendingBooking ID
	 */
	private void validateConflictWithSeatBooking(
		List<String> pendingSections,
		SeatBooking seatBooking,
		String pendingBookingId
	) {
		List<String> seatBookingSections = seatHoldKeyGenerator.generateSections(
			seatBooking.getDepartureStopOrder(),
			seatBooking.getArrivalStopOrder()
		);

		Set<String> conflictSections = new HashSet<>(pendingSections);
		conflictSections.retainAll(seatBookingSections);

		if (!conflictSections.isEmpty()) {
			log.error(
				"[구간 충돌] pendingBookingId={}, seatBookingId={}, seatId={}, conflictSections={}, pendingSections={}, seatBookingSections={}",
				pendingBookingId, seatBooking.getId(), seatBooking.getSeat().getId(), conflictSections, pendingSections, seatBookingSections);
			throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
		}
	}

	/**
	 * PendingBooking의 출발/도착 정류장에서 구간 목록 계산
	 */
	private List<String> calculateSections(PendingBooking pendingBooking) {
		ScheduleStop departureStop = getScheduleStop(pendingBooking.getDepartureStopId());
		ScheduleStop arrivalStop = getScheduleStop(pendingBooking.getArrivalStopId());

		return seatHoldKeyGenerator.generateSections(
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);
	}

	private ScheduleStop getScheduleStop(Long scheduleStopId) {
		return scheduleStopRepository.findById(scheduleStopId)
			.orElseThrow(() -> {
				log.error("[정류장 조회 실패] scheduleStopId={}", scheduleStopId);
				return new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND);
			});
	}

	private static List<Long> getSeatIds(PendingBooking pendingBooking) {
		return pendingBooking.getPendingSeatBookings().stream()
			.map(PendingSeatBooking::seatId)
			.toList();
	}
}
