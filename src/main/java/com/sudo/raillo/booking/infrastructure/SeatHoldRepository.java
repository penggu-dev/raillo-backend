package com.sudo.raillo.booking.infrastructure;

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
	 * @param trainCarId 객차 ID (Hold Index 키 생성용)
	 * @return SeatHoldResult 점유 결과 (성공/실패 + 충돌 정보)
	 */
	public SeatHoldResult tryHold(
		Long trainScheduleId,
		Long seatId,
		String pendingBookingId,
		int departureStopOrder,
		int arrivalStopOrder,
		Long trainCarId
	) {
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);
		String holdsKey = seatHoldKeyGenerator.generateHoldsKey(trainScheduleId, seatId);
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);

		log.debug("[좌석 Hold 시도] trainScheduleId={}, seatId={}, trainCarId={}, pendingBookingId={}, sections={}",
			trainScheduleId, seatId, trainCarId, pendingBookingId, sections);

		try {
			// Lua 스크립트 실행
			// - KEYS: [holdKey, holdsKey] - Redis 키들
			// - ARGV: [ttl, pendingBookingId, seatId, scheduleId, trainCarId, section1, section2, ...] - 인자들
			// - 반환: List<Object> (Lua table이 Java List로 변환됨)
			Object[] args = buildHoldArgs(pendingBookingId, seatId, trainScheduleId, trainCarId, sections);

			@SuppressWarnings("unchecked")  // DefaultRedisScript<List>의 raw type 때문에 필요
			List<Object> result = customStringRedisTemplate.execute(
				seatHoldScript,
				List.of(holdKey, holdsKey),
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
	 * 좌석 점유 해제
	 *
	 * <p>예약 취소 시 또는 TTL 만료 전 수동 해제가 필요할 때 사용.
	 * Hold가 이미 없어도 에러 발생하지 않음 (멱등성)</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID
	 * @param trainCarId 객차 ID (Hold Index 키 생성용)
	 * @param departureStopOrder 출발역 stopOrder (sections 생성용)
	 * @param arrivalStopOrder 도착역 stopOrder (sections 생성용)
	 */
	public void releaseHold(
		Long trainScheduleId,
		Long seatId,
		String pendingBookingId,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		String holdKey = seatHoldKeyGenerator.generateHoldKey(trainScheduleId, seatId, pendingBookingId);
		String holdsKey = seatHoldKeyGenerator.generateHoldsKey(trainScheduleId, seatId);
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);

		log.debug("[좌석 Hold 해제] trainScheduleId={}, seatId={}, trainCarId={}, pendingBookingId={}",
			trainScheduleId, seatId, trainCarId, pendingBookingId);

		try {
			Object[] args = buildReleaseArgs(pendingBookingId, seatId, trainScheduleId, trainCarId, sections);

			customStringRedisTemplate.execute(
				seatReleaseScript,
				List.of(holdKey, holdsKey),
				args
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
	 * Release 스크립트 인자 배열 구성
	 *
	 * <p>ARGV 형식: [pendingBookingId, seatId, scheduleId, trainCarId, section1, section2, ...]</p>
	 *
	 * @param pendingBookingId 예약 ID
	 * @param seatId 좌석 ID
	 * @param trainScheduleId 스케줄 ID
	 * @param trainCarId 객차 ID
	 * @param sections 구간 목록
	 * @return Lua ARGV로 전달할 인자 배열
	 */
	private Object[] buildReleaseArgs(
		String pendingBookingId,
		Long seatId,
		Long trainScheduleId,
		Long trainCarId,
		List<String> sections
	) {
		Object[] args = new Object[sections.size() + 4];
		args[0] = pendingBookingId;                      // ARGV[1]: pendingBookingId
		args[1] = String.valueOf(seatId);                // ARGV[2]: seatId
		args[2] = String.valueOf(trainScheduleId);       // ARGV[3]: scheduleId
		args[3] = String.valueOf(trainCarId);            // ARGV[4]: trainCarId
		for (int i = 0; i < sections.size(); i++) {
			args[i + 4] = sections.get(i);               // ARGV[5...]: sections
		}
		return args;
	}

	/**
	 * Hold 스크립트 인자 배열 구성
	 *
	 * <p>ARGV 형식: [ttl, pendingBookingId, seatId, scheduleId, trainCarId, section1, section2, ...]</p>
	 *
	 * @param pendingBookingId 예약 ID (holds 인덱스에 추가할 값)
	 * @param seatId 좌석 ID (Hold Index 멤버 생성용)
	 * @param trainScheduleId 스케줄 ID (Hold Index 키 생성용)
	 * @param trainCarId 객차 ID (Hold Index 키 생성용)
	 * @param sections 구간 목록 (예: ["0-1", "1-2", "2-3"])
	 * @return Lua ARGV로 전달할 인자 배열
	 */
	private Object[] buildHoldArgs(
		String pendingBookingId,
		Long seatId,
		Long trainScheduleId,
		Long trainCarId,
		List<String> sections
	) {
		Object[] args = new Object[sections.size() + 5];
		args[0] = String.valueOf(seatHoldTTLSeconds);    // ARGV[1]: TTL
		args[1] = pendingBookingId;                      // ARGV[2]: pendingBookingId
		args[2] = String.valueOf(seatId);                // ARGV[3]: seatId
		args[3] = String.valueOf(trainScheduleId);       // ARGV[4]: scheduleId
		args[4] = String.valueOf(trainCarId);            // ARGV[5]: trainCarId
		for (int i = 0; i < sections.size(); i++) {
			args[i + 5] = sections.get(i);               // ARGV[6...]: sections
		}
		return args;
	}
}
