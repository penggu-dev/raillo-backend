package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.HoldCountQuery;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatHoldRepository;
import com.sudo.raillo.booking.infrastructure.SeatHoldResult;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.global.redis.util.SeatHoldKeyGenerator;
import com.sudo.raillo.train.domain.ScheduleStop;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Seat Hold 서비스
 *
 * 비즈니스 로직과 Redis 작업 조율
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatHoldService {

	private final SeatHoldRepository seatHoldRepository;
	private final SeatHoldKeyGenerator seatHoldKeyGenerator;

	private static final Duration SEAT_HOLD_TTL_BUFFER = Duration.ofMinutes(1);

	/**
	 * Seat Hold 시도
	 * PendingBooking 생성 전에 호출하여 충돌 검사 수행
	 *
	 * <p>Seat Hold TTL은 PendingBooking TTL보다 1분 길게 설정하여
	 * PendingBooking이 만료되기 전에 Seat Hold가 먼저 사라지는 것을 방지한다.</p>
	 *
	 * @param pendingBookingId 미리 생성한 UUID (Seat Hold 키 식별자)
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param departureStop 출발 정차역
	 * @param arrivalStop 도착 정차역
	 * @param seatIds 점유할 좌석 ID 목록
	 * @param trainCarId 객차 ID (TrainCar Hold Index 키 생성용)
	 * @param pendingBookingTtl PendingBooking TTL
	 * @throws BusinessException 좌석 충돌 시 예외 발생
	 */
	public void holdSeats(
		String pendingBookingId,
		Long trainScheduleId,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		List<Long> seatIds,
		Long trainCarId,
		Duration pendingBookingTtl
	) {
		int departureStopOrder = departureStop.getStopOrder();
		int arrivalStopOrder = arrivalStop.getStopOrder();

		log.info("[좌석 Hold 요청] pendingBookingId={}, trainScheduleId={}, trainCarId={}, stopOrder={}->{}, seatCount={}",
			pendingBookingId, trainScheduleId, trainCarId, departureStopOrder, arrivalStopOrder, seatIds.size());

		Duration seatHoldTtl = pendingBookingTtl.plus(SEAT_HOLD_TTL_BUFFER);
		tryHoldSeats(trainScheduleId, seatIds, pendingBookingId, departureStopOrder, arrivalStopOrder, trainCarId, seatHoldTtl);
	}

	/**
	 * Seat Hold 해제
	 * TTL 만료 전 수동 해제 필요 시 사용
	 *
	 * @param pendingBookingId 예약 ID
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatIds pendingBooking에 속하는 좌석 ID 리스트
	 * @param trainCarId 객차 ID (TrainCar Hold Index 키 생성용)
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 */
	public void releaseSeats(
		String pendingBookingId,
		Long trainScheduleId,
		List<Long> seatIds,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		log.info("[좌석 Hold 해제 요청] pendingBookingId={}, trainScheduleId={}, seatCount={}",
			pendingBookingId, trainScheduleId, seatIds.size());

		for (Long seatId : seatIds) {
			try {
				seatHoldRepository.releaseSeatHold(
					trainScheduleId,
					seatId,
					pendingBookingId,
					trainCarId,
					departureStopOrder,
					arrivalStopOrder
				);
			} catch (Exception e) {
				log.error("[좌석 Hold 해제 실패] seatId={}, error={}", seatId, e.getMessage());
			}
		}
	}

	/**
	 * CarType별 Seat Hold 점유 좌석 수 계산 (Lua 스크립트 기반)
	 *
	 * <p>전달받은 trainCarIds에 대해 Lua 스크립트로 TrainCar Hold Index를 조회하여 Seat Hold 좌석 수를 계산</p>
	 * <p>Lua 내부에서 section 필터링 + seatId 중복 제거를 수행</p>
	 *
	 * @param trainScheduleId 스케줄 ID
	 * @param trainCarIds 조회할 객차 ID 목록 (동일 CarType, Facade에서 RDB 조회 후 전달)
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 * @return Seat Hold 점유 좌석 수
	 */
	public int getHoldSeatsCount(
		Long trainScheduleId,
		List<Long> trainCarIds,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		if (trainCarIds.isEmpty()) {
			return 0;
		}

		List<String> searchSections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);
		int seatHoldCount = seatHoldRepository.getHoldSeatsCount(trainScheduleId, trainCarIds, searchSections);

		log.debug("[Seat Hold 점유 수 계산] trainScheduleId={}, trainCarIds={}, stopOrder={}->{}, seatHoldCount={}",
			trainScheduleId, trainCarIds, departureStopOrder, arrivalStopOrder, seatHoldCount);

		return seatHoldCount;
	}

	/**
	 * Pipeline을 사용한 Seat Hold 점유 좌석 수 배치 조회
	 *
	 * <p>여러 건의 Hold 수 조회를 Redis Pipeline으로 묶어 한 번의 네트워크 왕복으로 처리</p>
	 *
	 * @param queries Hold 수 조회 요청 목록
	 * @return 각 쿼리별 Seat Hold 점유 좌석 수 (입력 순서와 동일)
	 */
	public List<Integer> getHoldSeatsCountBatch(List<HoldCountQuery> queries) {
		if (queries.isEmpty()) {
			return List.of();
		}

		List<List<String>> keysList = new ArrayList<>(queries.size());
		List<List<String>> sectionsList = new ArrayList<>(queries.size());

		for (HoldCountQuery query : queries) {
			List<String> keys = query.trainCarIds().stream()
				.map(carId -> seatHoldKeyGenerator.generateTrainCarHoldIndexKey(query.trainScheduleId(), carId))
				.toList();
			keysList.add(keys);

			List<String> sections = seatHoldKeyGenerator.generateSections(
				query.departureStopOrder(), query.arrivalStopOrder());
			sectionsList.add(sections);
		}

		List<Integer> results = seatHoldRepository.getHoldSeatsCountBatch(keysList, sectionsList);

		log.debug("[Seat Hold 점유 수 배치 조회] queryCount={}, results={}", queries.size(), results);
		return results;
	}

	/**
	 * 특정 객차에서 Seat Hold된 개별 좌석 ID 목록 조회
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param trainCarId 객차 ID
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 */
	public Set<Long> getSeatIdsOnHold(
		Long trainScheduleId,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);
		return seatHoldRepository.findSeatIdsOnHold(trainScheduleId, trainCarId, sections);
	}

	/**
	 * 여러 좌석 동시 Seat Hold 시도
	 *
	 * <p>모든 좌석에 대해 순차적으로 Seat Hold를 시도하고,
	 * 하나라도 실패하면 이미 성공한 좌석들을 롤백함</p>
	 */
	private void tryHoldSeats(
		Long trainScheduleId,
		List<Long> seatIds,
		String pendingBookingId,
		int departureStopOrder,
		int arrivalStopOrder,
		Long trainCarId,
		Duration seatHoldTtl
	) {
		log.info("[다중 좌석 Hold 시도] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		List<Long> successfulSeats = new ArrayList<>();

		try {
			for (Long seatId : seatIds) {
				SeatHoldResult result = seatHoldRepository.trySeatHold(
					trainScheduleId, seatId, pendingBookingId, departureStopOrder, arrivalStopOrder, trainCarId, seatHoldTtl);

				if (!result.success()) {
					rollbackHolds(
						trainScheduleId,
						successfulSeats,
						pendingBookingId,
						trainCarId,
						departureStopOrder,
						arrivalStopOrder
					);
					throwConflictException(result);
				}

				successfulSeats.add(seatId);
			}

			log.info("[다중 좌석 Hold 성공] trainScheduleId={}, seatCount={}, pendingBookingId={}",
				trainScheduleId, seatIds.size(), pendingBookingId);

		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			rollbackHolds(
				trainScheduleId,
				successfulSeats,
				pendingBookingId,
				trainCarId,
				departureStopOrder,
				arrivalStopOrder
			);
			log.error("[다중 좌석 Hold 오류] trainScheduleId={}, error={}", trainScheduleId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}

	private void rollbackHolds(
		Long trainScheduleId,
		List<Long> seatIds,
		String pendingBookingId,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		if (seatIds.isEmpty()) {
			return;
		}

		log.warn("[좌석 Hold 롤백] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		releaseSeats(pendingBookingId, trainScheduleId, seatIds, trainCarId, departureStopOrder, arrivalStopOrder);
	}

	private void throwConflictException(SeatHoldResult result) {
		if (result.isConflictWithHold()) {
			throw new BusinessException(BookingError.SEAT_CONFLICT_WITH_HOLD);
		} else {
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}
}
