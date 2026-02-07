package com.sudo.raillo.booking.application.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldResult;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

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

	private static final long SOLD_TTL_BUFFER_SECONDS = 3600; // 1시간 여유
	private static final long SOLD_TTL_MINIMUM_SECONDS = 3600; // 최소 1시간 보장 (음수 TTL 방어)

	private final SeatHoldRepository seatHoldRepository;
	private final TrainScheduleRepository trainScheduleRepository;

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
	 * 좌석 확정 (결제 완료 시)
	 * Hold → Sold 전환
	 *
	 * @param pendingBooking 예약 정보
	 */
	public void confirmSeats(PendingBooking pendingBooking) {
		Long trainScheduleId = pendingBooking.getTrainScheduleId();
		String pendingBookingId = pendingBooking.getId();
		List<Long> seatIds = extractSeatIds(pendingBooking);

		long soldTTLSeconds = calculateSoldTTL(trainScheduleId);

		confirmHoldSeats(trainScheduleId, seatIds, pendingBookingId, soldTTLSeconds);
	}

	/**
	 * 열차 도착 시간 기반 Sold TTL 계산
	 *
	 * <p>열차 도착 시간 + 1시간 여유분을 TTL로 설정하여,
	 * 열차 운행 완료 후 자동으로 Redis 메모리가 정리되도록 함</p>
	 *
	 * <p>방어 로직: 계산된 TTL이 최소값보다 작으면 최소 TTL 보장</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @return TTL (초 단위)
	 */
	private long calculateSoldTTL(Long trainScheduleId) {
		TrainSchedule trainSchedule = trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));

		LocalDate arrivalDate = trainSchedule.getOperationDate();
		LocalTime arrivalTime = trainSchedule.getArrivalTime();
		LocalTime departureTime = trainSchedule.getDepartureTime();

		// 도착시간이 출발시간보다 이르면 자정을 넘긴 것이므로 다음날로 처리
		if (arrivalTime.isBefore(departureTime)) {
			arrivalDate = arrivalDate.plusDays(1);
		}

		LocalDateTime arrivalDateTime = LocalDateTime.of(arrivalDate, arrivalTime);
		LocalDateTime now = LocalDateTime.now();

		long secondsUntilArrival = ChronoUnit.SECONDS.between(now, arrivalDateTime);
		long calculatedTTL = secondsUntilArrival + SOLD_TTL_BUFFER_SECONDS;

		// 방어 로직: TTL이 최소값보다 작으면 최소 TTL 보장
		if (calculatedTTL < SOLD_TTL_MINIMUM_SECONDS) {
			log.warn("[Sold TTL 방어 로직 적용] trainScheduleId={}, calculatedTTL={}s, minimumTTL={}s",
				trainScheduleId, calculatedTTL, SOLD_TTL_MINIMUM_SECONDS);
			return SOLD_TTL_MINIMUM_SECONDS;
		}

		return calculatedTTL;
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

	private void confirmHoldSeats(Long trainScheduleId, List<Long> seatIds, String pendingBookingId, long soldTTLSeconds) {
		log.info("[다중 좌석 확정 시도] trainScheduleId={}, seatIds={}, pendingBookingId={}, soldTTL={}s",
			trainScheduleId, seatIds, pendingBookingId, soldTTLSeconds);

		for (Long seatId : seatIds) {
			seatHoldRepository.confirmHold(trainScheduleId, seatId, pendingBookingId, soldTTLSeconds);
		}

		log.info("[다중 좌석 확정 성공] trainScheduleId={}, seatCount={}", trainScheduleId, seatIds.size());
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

	private List<Long> extractSeatIds(PendingBooking pb) {
		return pb.getPendingSeatBookings().stream()
			.map(PendingSeatBooking::seatId)
			.toList();
	}

	private void throwConflictException(SeatHoldResult result) {
		if (result.isConflictWithSold()) {
			throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_SOLD);
		} else if (result.isConflictWithHold()) {
			throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_HOLD);
		} else {
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}
}
