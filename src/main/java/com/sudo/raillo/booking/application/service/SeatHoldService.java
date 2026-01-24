package com.sudo.raillo.booking.application.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 좌석 임시 점유 서비스
 *
 * 비즈니스 로직과 Redis 작업 조율
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatHoldService {

	private final SeatHoldRepository seatHoldRepository;
	private final ScheduleStopRepository scheduleStopRepository;

	/**
	 * 좌석 임시 점유 시도
	 * PendingBooking 생성 전에 호출하여 충돌 검사 수행
	 *
	 * @param pendingBookingId 미리 생성한 UUID (Hold 키 식별자)
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param departureStopId 출발 정차역 ID
	 * @param arrivalStopId 도착 정차역 ID
	 * @param seatIds 점유할 좌석 ID 목록
	 * @throws BusinessException 좌석 충돌 시 예외 발생
	 */
	public void holdSeats(
		String pendingBookingId,
		Long trainScheduleId,
		Long departureStopId,
		Long arrivalStopId,
		List<Long> seatIds
	) {
		// 1. 출발/도착 정차역을 조회하여 stopOrder를 획득
		ScheduleStop departureStop = getScheduleStop(departureStopId);
		ScheduleStop arrivalStop = getScheduleStop(arrivalStopId);

		int departureStopOrder = departureStop.getStopOrder();
		int arrivalStopOrder = arrivalStop.getStopOrder();

		log.info("[좌석 Hold 요청] pendingBookingId={}, trainScheduleId={}, stopOrder={}->{}, seatCount={}",
			pendingBookingId, trainScheduleId, departureStopOrder, arrivalStopOrder, seatIds.size());

		// 2. 모든 좌석에 대해 Hold 시도 (하나라도 실패 시 롤백)
		seatHoldRepository.tryHoldSeats(
			trainScheduleId,
			seatIds,
			pendingBookingId,
			departureStopOrder,
			arrivalStopOrder
		);
	}

	/**
	 * // TODO : 결제 완료 시 호출 필요
	 * 좌석 확정 (결제 완료 시)
	 * Hold → Sold 전환
	 *
	 * @param pendingBooking 예약 정보
	 */
	public void confirmSeats(PendingBooking pendingBooking) {
		Long trainScheduleId = pendingBooking.getTrainScheduleId();
		String pendingBookingId = pendingBooking.getId();
		List<Long> seatIds = extractSeatIds(pendingBooking);

		log.info("[좌석 확정 요청] pendingBookingId={}, trainScheduleId={}, seatCount={}",
			pendingBookingId, trainScheduleId, seatIds.size());

		seatHoldRepository.confirmHoldSeats(trainScheduleId, seatIds, pendingBookingId);
	}

	/**
	 * 좌석 점유 해제
	 * TTL 만료 전 수동 해제 필요 시 사용
	 *
	 * @param pendingBookingId 예약 ID
	 * @param  trainScheduleId 열차 스케줄 ID
	 * @param seatIds pendingBooking에 속하는 좌석 ID 리스트
	 */
	public void releaseSeats(
		String pendingBookingId,
		Long trainScheduleId,
		List<Long> seatIds
	) {
		log.info("[좌석 Hold 해제 요청] pendingBookingId={}, trainScheduleId={}, seatCount={}",
			pendingBookingId, trainScheduleId, seatIds.size());

		seatHoldRepository.releaseHold(trainScheduleId, seatIds, pendingBookingId);
	}

	private ScheduleStop getScheduleStop(Long stopId) {
		return scheduleStopRepository.findById(stopId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}

	private List<Long> extractSeatIds(PendingBooking pb) {
		return pb.getPendingSeatBookings().stream()
			.map(PendingSeatBooking::seatId)
			.toList();
	}
}
