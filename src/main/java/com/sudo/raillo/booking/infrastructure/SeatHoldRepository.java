package com.sudo.raillo.booking.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
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
	 * <p>Lua 스크립트로 충돌 검사 + Hold 생성을 원자적으로 처리</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID (Hold 키 식별자)
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 * @return SeatHoldResult 점유 결과 (성공/실패 + 충돌 정보)
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
		String holdsKey = seatHoldKeyGenerator.generateHoldsKey(trainScheduleId, seatId);
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);

		log.debug("[좌석 Hold 시도] trainScheduleId={}, seatId={}, pendingBookingId={}, sections={}",
			trainScheduleId, seatId, pendingBookingId, sections);

		try {
			// Lua 스크립트 실행
			// - KEYS: [soldKey, holdKey, holdsKey] - Redis 키들
			// - ARGV: [ttl, pendingBookingId, section1, section2, ...] - 인자들
			// - 반환: List<Object> (Lua table이 Java List로 변환됨)
			Object[] args = buildHoldArgs(pendingBookingId, sections);

			@SuppressWarnings("unchecked")  // DefaultRedisScript<List>의 raw type 때문에 필요
			List<Object> result = customStringRedisTemplate.execute(
				seatHoldScript,
				List.of(soldKey, holdKey, holdsKey),
				args
			);

			// Lua 결과 파싱 (타입 캐스팅 + 예외 처리 포함)
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
	 * 좌석 확정 (Hold → Sold 전환)
	 *
	 * <p>결제 완료 시 호출하여 임시 점유를 확정 예약으로 전환</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID
	 * @throws BusinessException Hold가 없으면 SEAT_HOLD_NOT_FOUND 예외
	 */
	public void confirmHold(Long trainScheduleId, Long seatId, String pendingBookingId) {
		String soldKey = seatHoldKeyGenerator.generateSoldKey(trainScheduleId, seatId);
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);
		String holdsKey = seatHoldKeyGenerator.generateHoldsKey(trainScheduleId, seatId);

		log.debug("[좌석 확정 시도] trainScheduleId={}, seatId={}, pendingBookingId={}",
			trainScheduleId, seatId, pendingBookingId);

		try {
			@SuppressWarnings("unchecked")
			List<Object> result = customStringRedisTemplate.execute(
				seatConfirmScript,
				List.of(soldKey, holdKey, holdsKey),
				pendingBookingId
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
	 * 좌석 점유 해제
	 *
	 * <p>예약 취소 시 또는 TTL 만료 전 수동 해제가 필요할 때 사용.
	 * Hold가 이미 없어도 에러 발생하지 않음 (멱등성)</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID
	 */
	public void releaseHold(Long trainScheduleId, Long seatId, String pendingBookingId) {
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);
		String holdsKey = seatHoldKeyGenerator.generateHoldsKey(trainScheduleId, seatId);

		log.debug("[좌석 Hold 해제] trainScheduleId={}, seatId={}, pendingBookingId={}",
			trainScheduleId, seatId, pendingBookingId);

		try {
			customStringRedisTemplate.execute(
				seatReleaseScript,
				List.of(holdKey, holdsKey),
				pendingBookingId
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
	 * Hold 스크립트 인자 배열 구성
	 *
	 * <p>ARGV 형식: [ttl, pendingBookingId, section1, section2, ...]</p>
	 *
	 * @param pendingBookingId 예약 ID (holds 인덱스에 추가할 값)
	 * @param sections 구간 목록 (예: ["0-1", "1-2", "2-3"])
	 * @return Lua ARGV로 전달할 인자 배열
	 */
	private Object[] buildHoldArgs(String pendingBookingId, List<String> sections) {
		Object[] args = new Object[sections.size() + 2];
		args[0] = String.valueOf(seatHoldTTLSeconds);  // ARGV[1]: TTL
		args[1] = pendingBookingId;                    // ARGV[2]: pendingBookingId
		for (int i = 0; i < sections.size(); i++) {
			args[i + 2] = sections.get(i);             // ARGV[3...]: 구간들
		}
		return args;
	}

}
