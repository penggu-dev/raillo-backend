package com.sudo.raillo.booking.application.validator;

import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.global.redis.util.SeatHoldKeyGenerator;
import com.sudo.raillo.member.domain.Member;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Booking;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingValidator {

	private final SeatHoldKeyGenerator seatHoldKeyGenerator;
	private final SeatHoldRepository seatHoldRepository;
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
	 * 기존 예매들과 충돌 검증 (락이 걸린 상태에서 수행)
	 */
	public void validateConflictWithExistingBookings(
		Booking newBooking,
		List<SeatBooking> existingBookings
	) {
		int newDepartureOrder = newBooking.getDepartureStop().getStopOrder();
		int newArrivalOrder = newBooking.getArrivalStop().getStopOrder();

		existingBookings.forEach(existingBooking -> {
			int existingDepartureOrder = existingBooking.getBooking().getDepartureStop().getStopOrder();
			int existingArrivalOrder = existingBooking.getBooking().getArrivalStop().getStopOrder();
			if (existingDepartureOrder < newArrivalOrder && existingArrivalOrder > newDepartureOrder) {
				throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
			}
		});
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
	 * 결제 준비 시 좌석 충돌 검증
	 * - Redis Hold 구간과 DB SeatBooking 구간 비교
	 * - trainScheduleId별로 그룹핑하여 배치 처리
	 * - 같은 스케줄에 해당하는 예약을 그룹핑하여 처리
	 *
	 * @param pendingBookings 결제할 PendingBooking 목록
	 */
	public void validateSeatConflicts(List<PendingBooking> pendingBookings) {
		// trainScheduleId 별로 그룹핑
		Map<Long, List<PendingBooking>> pendingBookingsMap = pendingBookings.stream()
			.collect(Collectors.groupingBy(PendingBooking::getTrainScheduleId));

		pendingBookingsMap.forEach(this::validateSeatConflictsForSchedule);
	}

	/**
	 * 특정 열차 스케줄에 대한 좌석 충돌 검증
	 */
	private void validateSeatConflictsForSchedule(
		Long trainScheduleId,
		List<PendingBooking> pendingBookings
	) {
		List<Long> allSeatIds = pendingBookings.stream()
			.flatMap(pb -> pb.getPendingSeatBookings().stream())
			.map(PendingSeatBooking::seatId)
			.toList();

		List<SeatBooking> allExistingSeatBookings = seatBookingRepository.findByTrainScheduleIdAndSeatIds(
			trainScheduleId, allSeatIds
		);

		if(allExistingSeatBookings.isEmpty()) {
			return;
		}

		// SeatBooking을 seatId 별로 그룹핑
		Map<Long, List<SeatBooking>> seatBookingBySeatId = allExistingSeatBookings.stream()
			.collect(Collectors.groupingBy(sb -> sb.getSeat().getId()));

		for (PendingBooking pendingBooking : pendingBookings) {
			// 1. 해당 PendingBooking의 SeatId 목록 추출
			List<Long> seatIds = pendingBooking.getPendingSeatBookings().stream()
				.map(PendingSeatBooking::seatId)
				.toList();

			if (seatIds.isEmpty()) {
				continue;
			}

			// 2. Redis에서 Hold 구간 조회
			Map<Long, Set<String>> holdSectionsBySeat = seatHoldRepository.getHoldSections(
				trainScheduleId, seatIds, pendingBooking.getId()
			);

			// 3. 좌석 별로 충돌 검증
			for (Long seatId : seatIds) {
				Set<String> holdSections = holdSectionsBySeat.getOrDefault(seatId, Set.of());

				if (holdSections.isEmpty()) {
					log.warn("[Hold 구간 없음] pendingBookingId={}, seatId={}", pendingBooking.getId(), seatId);
					throw new BusinessException(BookingError.SEAT_HOLD_SECTION_NOT_FOUND);
				}

				List<SeatBooking> seatBookings = seatBookingBySeatId.getOrDefault(seatId, List.of());

				if (seatBookings.isEmpty()) {
					continue;
				}

				try {
					validateConflictWithSeatBookings(holdSections, seatBookings);
				} catch (BusinessException e) {
					log.error("[구간 충돌] pendingBookingId={}, seatId={}", pendingBooking.getId(), seatId);
					throw e;
				}
			}
		}
	}

	/**
	 * 임시 점유 구간(Hold)과 SeatBooking의 예매 좌석 구간 충돌 검증
	 * @param holdSections 임시 점유 구간 리스트 (예: ["1-2", "2-3", "3-4"])
	 * @param seatBookings 예매된 좌석 목록
	 */
	private void validateConflictWithSeatBookings(
		Set<String> holdSections,
		List<SeatBooking> seatBookings
	) {
		for (SeatBooking seatBooking : seatBookings) {
			List<String> seatBookingSections = seatHoldKeyGenerator.generateSections(
				seatBooking.getDepartureStopOrder(),
				seatBooking.getArrivalStopOrder()
			);

			Set<String> conflictSections = new HashSet<>(holdSections);
			conflictSections.retainAll(seatBookingSections);

			if (!conflictSections.isEmpty()) {
				log.error(
					"[구간 충돌] seatBookingId={}, seatId={}, conflictSections={}, holdSections={}, seatBookingSections={}",
					seatBooking.getId(), seatBooking.getSeat().getId(), conflictSections, holdSections,
					seatBookingSections
				);
				throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
			}
		}
	}
}
