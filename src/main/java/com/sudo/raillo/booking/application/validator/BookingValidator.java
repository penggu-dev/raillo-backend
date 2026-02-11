package com.sudo.raillo.booking.application.validator;

import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.member.domain.Member;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	 * 출발 시간이 현재 시간보다 이전인지 검증
	 * <p>자정을 넘기는 야간 열차의 경우 operationDate + 1일로 계산
	 */
	public void validateDepartureTimeNotPassed(TrainSchedule trainSchedule, ScheduleStop departureStop) {
		LocalDate departureDate = trainSchedule.getOperationDate();
		LocalTime stopDepartureTime = departureStop.getDepartureTime();
		LocalTime trainDepartureTime = trainSchedule.getDepartureTime();

		// 정차역 출발시간이 열차 출발시간보다 이르면 자정을 넘긴 것이므로 다음날로 처리
		if(stopDepartureTime.isBefore(trainDepartureTime)) {
			departureDate = departureDate.plusDays(1);
		}

		LocalDateTime departureDateTime = LocalDateTime.of(departureDate, stopDepartureTime);

		if(departureDateTime.isBefore(LocalDateTime.now())) {
			throw new BusinessException(TrainErrorCode.DEPARTURE_TIME_PASSED);
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
	public void validateAllPendingBookingsExist(List<String> pendingBookingIds, Map<String, PendingBooking> bookingsById) {
		List<String> notFoundIds = pendingBookingIds.stream()
			.filter(id -> !bookingsById.containsKey(id))
			.toList();

		if (!notFoundIds.isEmpty()) {
			log.warn("[임시 예약 만료] pendingBookingIds={} - TTL 만료 또는 이미 사용됨", notFoundIds);
			throw new BusinessException(BookingError.PENDING_BOOKING_EXPIRED);
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
	 * PendingBooking 생성 시 좌석 충돌 검증
	 * <p>DB SeatBooking과 충돌 검증 - PendingBooking 생성 전 조기 실패</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param departureStopId 출발 정류장 ID
	 * @param arrivalStopId 도착 정류장 ID
	 * @param seatIds 좌석 ID 목록
	 */
	public void validateSeatConflicts(
		Long trainScheduleId,
		Long departureStopId,
		Long arrivalStopId,
		List<Long> seatIds
	) {
		// 1. ScheduleStop 조회
		ScheduleStop departureStop = scheduleStopRepository.findById(departureStopId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.SCHEDULE_STOP_NOT_FOUND));

		ScheduleStop arrivalStop = scheduleStopRepository.findById(arrivalStopId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.SCHEDULE_STOP_NOT_FOUND));

		// 2. DB에서 구간 겹침 조건으로 충돌 예약 조회
		List<SeatBooking> conflictingBookings = seatBookingRepository.findConflictingSeatBookings(
			trainScheduleId, seatIds,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);

		// 3. 충돌 행이 있으면 예외 발생
		if (!conflictingBookings.isEmpty()) {
			log.error("[구간 충돌] seatId={}, booked=[{}-{}], request=[{}-{}]",
				conflictingBookings.get(0).getSeat().getId(),
				conflictingBookings.get(0).getDepartureStopOrder(),
				conflictingBookings.get(0).getArrivalStopOrder(),
				departureStop.getStopOrder(), arrivalStop.getStopOrder());
			throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
		}
	}

	/**
	 * 결제 준비 시 좌석 충돌 검증
	 * <p>Redis Hold 구간과 DB SeatBooking 구간 비교</p>
	 * @param pendingBookings 결제할 PendingBooking 목록
	 */
	public void validateSeatConflicts(List<PendingBooking> pendingBookings) {
		// 1. 필요한 ScheduleStop ID들을 한 번에 수집하여 한 번의 쿼리로 조회
		Set<Long> stopIds = pendingBookings.stream()
			.flatMap(pb -> Stream.of(pb.getDepartureStopId(), pb.getArrivalStopId()))
			.collect(Collectors.toSet());

		Map<Long, ScheduleStop> stopMap = scheduleStopRepository.findAllById(stopIds)
			.stream()
			.collect(Collectors.toMap(ScheduleStop::getId, Function.identity()));

		// 2. 정류장 조회 결과 검증
		validateAllStopsExist(stopIds, stopMap);

		for (PendingBooking pendingBooking : pendingBookings) {
			Long trainScheduleId = pendingBooking.getTrainScheduleId();
			List<Long> seatIds = getSeatIds(pendingBooking);

			// 3. stopOrder 추출
			ScheduleStop departureStop = stopMap.get(pendingBooking.getDepartureStopId());
			ScheduleStop arrivalStop = stopMap.get(pendingBooking.getArrivalStopId());

			// 4. DB에서 구간 겹침 조건으로 충돌 예약 조회
			List<SeatBooking> conflictingBookings = seatBookingRepository.findConflictingSeatBookings(
				trainScheduleId,
				seatIds,
				departureStop.getStopOrder(),
				arrivalStop.getStopOrder()
			);

			// 5. 충돌 행이 있으면 예외 발생
			if (!conflictingBookings.isEmpty()) {
				log.error("[구간 충돌] pendingBookingId={}, seatId={}, booked=[{}-{}], request=[{}-{}]",
					pendingBooking.getId(),
					conflictingBookings.get(0).getSeat().getId(),
					conflictingBookings.get(0).getDepartureStopOrder(),
					conflictingBookings.get(0).getArrivalStopOrder(),
					departureStop.getStopOrder(), arrivalStop.getStopOrder());
				throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
			}
		}
	}

	private void validateAllStopsExist(Set<Long> stopIds, Map<Long, ScheduleStop> stopMap) {
		if(stopIds.size() != stopMap.size()) {
			Set<Long> noExistIds = stopIds.stream()
				.filter(id -> !stopMap.containsKey(id))
				.collect(Collectors.toSet());
			log.error("[정류장 조회 실패] scheduleStopIds={}", noExistIds);
			throw new BusinessException(TrainErrorCode.SCHEDULE_STOP_NOT_FOUND);
		}
	}

	private static List<Long> getSeatIds(PendingBooking pendingBooking) {
		return pendingBooking.getPendingSeatBookings().stream()
			.map(PendingSeatBooking::seatId)
			.toList();
	}
}
