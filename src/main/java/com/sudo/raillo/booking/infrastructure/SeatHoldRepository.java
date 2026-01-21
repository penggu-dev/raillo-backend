package com.sudo.raillo.booking.infrastructure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.global.redis.util.SeatHoldKeyGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 좌석 임시 점유(Hold) Redis Repository
 *
 * <p>Lua 스크립트를 사용하여 좌석 구간 충돌 검사 및 임시 점유를 원자적으로 처리</p>
 *
 * <h3>Lua 스크립트 실행 흐름</h3>
 * <ol>
 *   <li>RedisTemplate.execute(script, keys, args) 호출</li>
 *   <li>Lua 스크립트가 Redis 서버에서 원자적으로 실행</li>
 *   <li>결과를 List&lt;Object&gt;로 반환 (Lua table → Java List)</li>
 *   <li>SeatHoldResult.fromLuaResult()로 파싱</li>
 * </ol>
 *
 * @see com.sudo.raillo.global.config.RedisScriptConfig - 스크립트 Bean 등록
 * @see SeatHoldResult - 스크립트 결과 파싱
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SeatHoldRepository {

	private final RedisTemplate<String, String> customStringRedisTemplate;
	private final SeatHoldKeyGenerator seatHoldKeyGenerator;
	private final DefaultRedisScript<List> seatHoldScript;
	private final DefaultRedisScript<List> seatConfirmScript;
	private final DefaultRedisScript<List> seatReleaseScript;

	@Value("${redis.ttl.seat-hold:600}")
	private long seatHoldTTLSeconds;

	/**
	 * 좌석 임시 점유 시도
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID (Hold 키 식별자)
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 * @return SeatHoldResult 점유 결과
	 */
	public SeatHoldResult tryHold(
		Long trainScheduleId,
		Long seatId,
		String pendingBookingId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		String soldKey = seatHoldKeyGenerator.generateSoldKey(trainScheduleId, seatId);
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);

		log.debug("[좌석 Hold 시도] trainScheduleId={}, seatId={}, pendingBookingId={}, sections={}",
			trainScheduleId, seatId, pendingBookingId, sections);

		try {
			// Lua 스크립트 실행
			// KEYS: [soldKey, holdKey]
			// ARGV: [ttl, section1, section2, ...]
			Object[] args = buildHoldArgs(sections);

			@SuppressWarnings("unchecked")
			List<Object> result = customStringRedisTemplate.execute(
				seatHoldScript,
				List.of(soldKey, holdKey),
				args
			);

			SeatHoldResult holdResult = SeatHoldResult.fromLuaResult(result);

			if (holdResult.success()) {
				log.info("[좌석 Hold 성공] trainScheduleId={}, seatId={}, pendingBookingId={}",
					trainScheduleId, seatId, pendingBookingId);
			} else {
				log.warn("[좌석 Hold 실패] trainScheduleId={}, seatId={}, status={}, conflictSection={}",
					trainScheduleId, seatId, holdResult.status(), holdResult.conflictSection());
			}

			return holdResult;

		} catch (Exception e) {
			log.error("[좌석 Hold 스크립트 오류] trainScheduleId={}, seatId={}, error={}",
				trainScheduleId, seatId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}

	/**
	 * 여러 좌석 동시 임시 점유 시도
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatIds 좌석 ID 목록
	 * @param pendingBookingId 예약 ID
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 * @throws BusinessException 하나라도 충돌 시 예외 발생, 이미 점유된 좌석은 롤백
	 */
	public void tryHoldSeats(
		Long trainScheduleId,
		List<Long> seatIds,
		String pendingBookingId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		log.info("[다중 좌석 Hold 시도] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		// 이미 성공한 좌석들 (롤백용)
		List<Long> successfulSeats = new ArrayList<>();

		try {
			for (Long seatId : seatIds) {
				SeatHoldResult result = tryHold(
					trainScheduleId, seatId, pendingBookingId,
					departureStopOrder, arrivalStopOrder
				);

				if (!result.success()) {
					// 실패 시 이미 점유한 좌석들 롤백
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
			// 예상치 못한 오류 시 롤백
			rollbackHolds(trainScheduleId, successfulSeats, pendingBookingId);
			log.error("[다중 좌석 Hold 오류] trainScheduleId={}, error={}", trainScheduleId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}

	/**
	 * 좌석 확정 (Hold → Sold)
	 * 결제 완료 시 호출
	 */
	public void confirmHold(Long trainScheduleId, Long seatId, String pendingBookingId) {
		String soldKey = seatHoldKeyGenerator.generateSoldKey(trainScheduleId, seatId);
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);

		log.debug("[좌석 확정 시도] trainScheduleId={}, seatId={}, pendingBookingId={}",
			trainScheduleId, seatId, pendingBookingId);

		try {
			@SuppressWarnings("unchecked")
			List<Object> result = customStringRedisTemplate.execute(
				seatConfirmScript,
				List.of(soldKey, holdKey)
			);

			SeatHoldResult confirmResult = SeatHoldResult.fromLuaResult(result);

			if (!confirmResult.success()) {
				log.warn("[좌석 확정 실패 - Hold 없음] trainScheduleId={}, seatId={}, pendingBookingId={}",
					trainScheduleId, seatId, pendingBookingId);
				throw new BusinessException(BookingError.SEAT_HOLD_NOT_FOUND);
			}

			log.info("[좌석 확정 성공] trainScheduleId={}, seatId={}, pendingBookingId={}",
				trainScheduleId, seatId, pendingBookingId);

		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			log.error("[좌석 확정 오류] trainScheduleId={}, seatId={}, error={}",
				trainScheduleId, seatId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_CONFIRM_FAILED);
		}
	}

	/**
	 * 여러 좌석 확정
	 */
	public void confirmHoldSeats(Long trainScheduleId, List<Long> seatIds, String pendingBookingId) {
		log.info("[다중 좌석 확정 시도] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		for (Long seatId : seatIds) {
			confirmHold(trainScheduleId, seatId, pendingBookingId);
		}

		log.info("[다중 좌석 확정 성공] trainScheduleId={}, seatCount={}", trainScheduleId, seatIds.size());
	}

	/**
	 * 좌석 점유 해제 (예약 취소 또는 타임아웃 수동 처리)
	 */
	public void releaseHold(Long trainScheduleId, Long seatId, String pendingBookingId) {
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);

		log.debug("[좌석 Hold 해제] trainScheduleId={}, seatId={}, pendingBookingId={}",
			trainScheduleId, seatId, pendingBookingId);

		try {
			customStringRedisTemplate.execute(
				seatReleaseScript,
				List.of(holdKey)
			);

			log.info("[좌석 Hold 해제 완료] trainScheduleId={}, seatId={}, pendingBookingId={}",
				trainScheduleId, seatId, pendingBookingId);

		} catch (Exception e) {
			log.error("[좌석 Hold 해제 오류] trainScheduleId={}, seatId={}, error={}",
				trainScheduleId, seatId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_RELEASE_FAILED);
		}
	}

	/**
	 * 여러 좌석 점유 해제
	 */
	public void releaseHold(Long trainScheduleId, List<Long> seatIds, String pendingBookingId) {
		log.info("[다중 좌석 Hold 해제] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		for (Long seatId : seatIds) {
			releaseHold(trainScheduleId, seatId, pendingBookingId);
		}
	}

	/**
	 * Hold 스크립트 인자 구성
	 */
	private Object[] buildHoldArgs(List<String> sections) {
		Object[] args = new Object[sections.size() + 1];
		args[0] = String.valueOf(seatHoldTTLSeconds);
		for (int i = 0; i < sections.size(); i++) {
			args[i + 1] = sections.get(i);
		}
		return args;
	}

	/**
	 * 충돌 시 롤백
	 */
	private void rollbackHolds(Long trainScheduleId, List<Long> seatIds, String pendingBookingId) {
		if (seatIds.isEmpty()) {
			return;
		}

		log.warn("[좌석 Hold 롤백 시도] trainScheduleId={}, seatIds={}, pendingBookingId={}",
			trainScheduleId, seatIds, pendingBookingId);

		for (Long seatId : seatIds) {
			try {
				releaseHold(trainScheduleId, seatId, pendingBookingId);
			} catch (Exception e) {
				log.error("[좌석 Hold 롤백 실패] seatId={}, error={}", seatId, e.getMessage());
				// 롤백 실패해도 계속 진행 (TTL로 자동 해제됨)
			}
		}
	}

	/**
	 * 충돌 타입에 따른 예외 발생
	 */
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
