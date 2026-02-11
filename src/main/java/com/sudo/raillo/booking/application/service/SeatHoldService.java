package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldResult;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

	/**
	 * 좌석 임시 점유 시도
	 * PendingBooking 생성 전에 호출하여 충돌 검사 수행
	 *
	 * @param pendingBookingId 미리 생성한 UUID (Hold 키 식별자)
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param departureStop 출발 정차역
	 * @param arrivalStop 도착 정차역
	 * @param seatIds 점유할 좌석 ID 목록
	 * @throws BusinessException 좌석 충돌 시 예외 발생
	 */
	public void holdSeats(
		String pendingBookingId,
		Long trainScheduleId,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Long> seatIds
	) {
		int departureStopOrder = departureStop.getStopOrder();
		int arrivalStopOrder = arrivalStop.getStopOrder();

		log.info("[좌석 Hold 요청] pendingBookingId={}, trainScheduleId={}, stopOrder={}->{}, seatCount={}",
			pendingBookingId, trainScheduleId, departureStopOrder, arrivalStopOrder, seatIds.size());

		tryHoldSeats(trainScheduleId, seatIds, pendingBookingId, departureStopOrder, arrivalStopOrder);
	}

	/**
	 * 좌석 점유 해제
	 * TTL 만료 전 수동 해제 필요 시 사용
	 *
	 * @param pendingBookingId 예약 ID
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatIds pendingBooking에 속하는 좌석 ID 리스트
	 */
	public void releaseSeats(
		String pendingBookingId,
		Long trainScheduleId,
		List<Long> seatIds
	) {
		log.info("[좌석 Hold 해제 요청] pendingBookingId={}, trainScheduleId={}, seatCount={}",
			pendingBookingId, trainScheduleId, seatIds.size());

		for (Long seatId : seatIds) {
			seatHoldRepository.releaseHold(trainScheduleId, seatId, pendingBookingId);
		}
	}

	/**
	 * 여러 좌석 동시 임시 점유 시도
	 *
	 * <p>모든 좌석에 대해 순차적으로 Hold를 시도하고,
	 * 하나라도 실패하면 이미 성공한 좌석들을 롤백함</p>
	 */
	private void tryHoldSeats(
		Long trainScheduleId,
		List<Long> seatIds,
		String pendingBookingId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		log.info("[다중 좌석 Hold 시도] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		List<Long> successfulSeats = new ArrayList<>();

		try {
			for (Long seatId : seatIds) {
				SeatHoldResult result = seatHoldRepository.tryHold(
					trainScheduleId, seatId, pendingBookingId,
					departureStopOrder, arrivalStopOrder
				);

				if (!result.success()) {
					rollbackHolds(trainScheduleId, successfulSeats, pendingBookingId);
					throwConflictException(result);
				}

				successfulSeats.add(seatId);
			}

			log.info("[다중 좌석 Hold 성공] trainScheduleId={}, seatCount={}, pendingBookingId={}",
				trainScheduleId, seatIds.size(), pendingBookingId);

		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			rollbackHolds(trainScheduleId, successfulSeats, pendingBookingId);
			log.error("[다중 좌석 Hold 오류] trainScheduleId={}, error={}", trainScheduleId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}

	private void rollbackHolds(Long trainScheduleId, List<Long> seatIds, String pendingBookingId) {
		if (seatIds.isEmpty()) {
			return;
		}

		log.warn("[좌석 Hold 롤백] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		for (Long seatId : seatIds) {
			try {
				seatHoldRepository.releaseHold(trainScheduleId, seatId, pendingBookingId);
			} catch (Exception e) {
				log.error("[롤백 실패] seatId={}, error={}", seatId, e.getMessage());
			}
		}
	}

	private void throwConflictException(SeatHoldResult result) {
		if (result.isConflictWithHold()) {
			throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_HOLD);
		} else {
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}
}
