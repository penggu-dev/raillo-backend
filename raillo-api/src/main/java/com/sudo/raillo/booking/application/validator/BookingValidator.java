package com.sudo.raillo.booking.application.validator;

import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.member.domain.Member;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.exception.TrainError;
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
			log.warn("[예약 만료] pendingBookingIds={} - TTL 만료 또는 이미 사용됨", notFoundIds);
			throw new BusinessException(BookingError.PENDING_BOOKING_EXPIRED);
		}
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
	 * <p>Seat Hold 구간과 DB SeatBooking 구간 비교</p>
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
			List<Long> seatIds = pendingBooking.getSeatIds();

			// 3. stopOrder 추출
			ScheduleStop departureStop = stopMap.get(pendingBooking.getDepartureStopId());
			ScheduleStop arrivalStop = stopMap.get(pendingBooking.getArrivalStopId());

			// 4. DB에서 구간 겹침 조건으로 충돌 예약 조회
			validateSeatConflicts(trainScheduleId, departureStop, arrivalStop, seatIds);
		}
	}

	/**
	 * PendingBooking 생성 시 확정 좌석 중 구간 중복 여부를 검증한다.
	 * <p>Repository는 구간이 겹치는 SeatBooking을 조회하고, 충돌 판단은 Validator에서 수행한다.</p>
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
		// DB에서 요청 구간과 겹치는 확정 좌석 조회
		List<SeatBooking> overlappingSeatBookings = seatBookingRepository.findOverlappingSeatBookings(
			trainScheduleId,
			seatIds,
			departureStop.getStopOrder(),
			arrivalStop.getStopOrder()
		);

		// 충돌 행이 있으면 예외 발생
		if (!overlappingSeatBookings.isEmpty()) {
			log.error("[구간 충돌] seatId={}, booked=[{}-{}], request=[{}-{}]",
				overlappingSeatBookings.get(0).getSeat().getId(),
				overlappingSeatBookings.get(0).getDepartureStopOrder(),
				overlappingSeatBookings.get(0).getArrivalStopOrder(),
				departureStop.getStopOrder(), arrivalStop.getStopOrder());
			throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_SOLD);
		}
	}

	private void validateAllStopsExist(Set<Long> stopIds, Map<Long, ScheduleStop> stopMap) {
		if(stopIds.size() != stopMap.size()) {
			Set<Long> noExistIds = stopIds.stream()
				.filter(id -> !stopMap.containsKey(id))
				.collect(Collectors.toSet());
			log.error("[정류장 조회 실패] scheduleStopIds={}", noExistIds);
			throw new BusinessException(TrainError.SCHEDULE_STOP_NOT_FOUND);
		}
	}


}
