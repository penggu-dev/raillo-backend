package com.sudo.raillo.booking.infrastructure;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.global.redis.util.SeatHoldKeyGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Seat Hold Redis Repository
 *
 * <p>Lua 스크립트를 사용하여 좌석 구간 충돌 검사 및 Seat Hold를 원자적으로 처리</p>
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
	private final DefaultRedisScript<Long> getHoldSeatsCountScript;

	/**
	 * Seat Hold 시도
	 *
	 * <p>Lua 스크립트로 충돌 검사 + Seat Hold 생성을 원자적으로 처리</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID (Seat Hold 키 식별자)
	 * @param departureStopOrder 출발역 stopOrder
	 * @param arrivalStopOrder 도착역 stopOrder
	 * @param trainCarId 객차 ID (TrainCar Hold Index 키 생성용)
	 * @param seatHoldTtl Seat Hold TTL
	 * @return SeatHoldResult 점유 결과 (성공/실패 + 충돌 정보)
	 */
	public SeatHoldResult trySeatHold(
		Long trainScheduleId,
		Long seatId,
		String pendingBookingId,
		int departureStopOrder,
		int arrivalStopOrder,
		Long trainCarId,
		Duration seatHoldTtl
	) {
		long seatHoldTtlSeconds = Math.max(1L, seatHoldTtl.toSeconds());
		String seatHoldKey = seatHoldKeyGenerator.generateSeatHoldKey(trainScheduleId, seatId, pendingBookingId);
		String seatHoldIndexKey = seatHoldKeyGenerator.generateSeatHoldIndexKey(trainScheduleId, seatId);
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);

		log.debug("[좌석 Hold 시도] trainScheduleId={}, seatId={}, trainCarId={}, pendingBookingId={}, sections={}",
			trainScheduleId, seatId, trainCarId, pendingBookingId, sections);

		String trainCarHoldIndexKey = seatHoldKeyGenerator.generateTrainCarHoldIndexKey(trainScheduleId, trainCarId);

		try {
			// Lua 스크립트 실행
			// - KEYS: [seatHoldKey, seatHoldIndexKey, trainCarHoldIndexKey] - Redis 키들
			// - ARGV: [ttl, pendingBookingId, seatId, section1, section2, ...] - 인자들
			// - 반환: List<Object> (Lua table이 Java List로 변환됨)
			Object[] args = buildSeatHoldArgs(pendingBookingId, seatId, sections, seatHoldTtlSeconds);

			@SuppressWarnings("unchecked")  // DefaultRedisScript<List>의 raw type 때문에 필요
			List<Object> result = customStringRedisTemplate.execute(
				seatHoldScript,
				List.of(seatHoldKey, seatHoldIndexKey, trainCarHoldIndexKey),
				args
			);

			// Lua 결과 파싱 (타입 캐스팅 + 예외 처리 포함)
			SeatHoldResult seatHoldResult = SeatHoldResult.fromLuaResult(result);

			if (seatHoldResult.success()) {
				log.info("[좌석 Hold 성공] trainScheduleId={}, seatId={}, pendingBookingId={}",
					trainScheduleId, seatId, pendingBookingId);
			} else {
				log.warn("[좌석 Hold 실패] trainScheduleId={}, seatId={}, status={}, conflictSection={}",
					trainScheduleId, seatId, seatHoldResult.status(), seatHoldResult.conflictSection());
			}

			return seatHoldResult;

		} catch (Exception e) {
			log.error("[좌석 Hold 스크립트 오류] trainScheduleId={}, seatId={}, error={}",
				trainScheduleId, seatId, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
	}

	/**
	 * Seat Hold 해제
	 *
	 * <p>예약 취소 시 또는 TTL 만료 전 수동 해제가 필요할 때 사용.
	 * Seat Hold가 이미 없어도 에러 발생하지 않음 (멱등성)</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param seatId 좌석 ID
	 * @param pendingBookingId 예약 ID
	 * @param trainCarId 객차 ID (TrainCar Hold Index 키 생성용)
	 * @param departureStopOrder 출발역 stopOrder (sections 생성용)
	 * @param arrivalStopOrder 도착역 stopOrder (sections 생성용)
	 */
	public void releaseSeatHold(
		Long trainScheduleId,
		Long seatId,
		String pendingBookingId,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		String seatHoldKey = seatHoldKeyGenerator.generateSeatHoldKey(trainScheduleId, seatId, pendingBookingId);
		String seatHoldIndexKey = seatHoldKeyGenerator.generateSeatHoldIndexKey(trainScheduleId, seatId);
		List<String> sections = seatHoldKeyGenerator.generateSections(departureStopOrder, arrivalStopOrder);

		log.debug("[좌석 Hold 해제] trainScheduleId={}, seatId={}, trainCarId={}, pendingBookingId={}",
			trainScheduleId, seatId, trainCarId, pendingBookingId);

		String trainCarHoldIndexKey = seatHoldKeyGenerator.generateTrainCarHoldIndexKey(trainScheduleId, trainCarId);

		try {
			Object[] args = buildReleaseArgs(pendingBookingId, seatId, sections);

			customStringRedisTemplate.execute(
				seatReleaseScript,
				List.of(seatHoldKey, seatHoldIndexKey, trainCarHoldIndexKey),
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
	 * CarType별 Seat Hold 점유 좌석 수 계산 (Lua 스크립트 사용)
	 *
	 * <p>여러 객차의 TrainCar Hold Index를 한 번에 조회하여 Seat Hold 점유 좌석 수를 계산</p>
	 * <p>Lua 스크립트에서 section 필터링 및 seatId 중복 제거를 수행</p>
	 *
	 * @param trainScheduleId 스케줄 ID
	 * @param trainCarIds 조회할 객차 ID 목록 (동일 CarType)
	 * @param sections 검색 구간 목록 (예: ["0-1", "1-2"])
	 * @return Seat Hold 점유 좌석 수
	 */
	public int getHoldSeatsCount(
		Long trainScheduleId,
		List<Long> trainCarIds,
		List<String> sections
	) {
		// KEYS: TrainCar Hold Index 키 목록 생성
		List<String> keys = trainCarIds.stream()
			.map(carId -> seatHoldKeyGenerator.generateTrainCarHoldIndexKey(trainScheduleId, carId))
			.toList();

		Object[] args = buildHoldSeatsCountArgs(sections);

		try {
			Long count = customStringRedisTemplate.execute(
				getHoldSeatsCountScript,
				keys,
				args
			);
			return count.intValue();

		} catch (Exception e) {
			log.error("[Seat Hold 점유 수 조회 오류] trainScheduleId={}, trainCarIds={}, error={}",
				trainScheduleId, trainCarIds, e.getMessage(), e);
			// Seat Hold는 검색 정확도 보조 데이터이므로 레디스 오류 시 0 반환하여 검색 가용성 유지
			return 0;
		}
	}

	/**
	 * Pipeline을 사용한 Seat Hold 점유 좌석 수 배치 조회
	 *
	 * <p>여러 건의 Hold 수 조회를 Redis Pipeline으로 묶어 한 번의 네트워크 왕복으로 처리</p>
	 *
	 * @param keysList 각 쿼리별 TrainCar Hold Index 키 목록
	 * @param sectionsList 각 쿼리별 검색 구간 목록
	 * @return 각 쿼리별 Seat Hold 점유 좌석 수
	 */
	public List<Integer> getHoldSeatsCountBatch(
		List<List<String>> keysList,
		List<List<String>> sectionsList
	) {
		try {
			List<Object> results = customStringRedisTemplate.executePipelined(new SessionCallback<>() {
				@Override
				@SuppressWarnings("unchecked")
				public Object execute(RedisOperations operations) throws DataAccessException {
					for (int i = 0; i < keysList.size(); i++) {
						List<String> keys = keysList.get(i);
						Object[] args = buildHoldSeatsCountArgs(sectionsList.get(i));
						operations.execute(getHoldSeatsCountScript, keys, args);
					}
					return null;
				}
			});

			List<Integer> counts = new ArrayList<>(results.size());
			for (Object result : results) {
				counts.add(result instanceof Long l ? l.intValue() : 0);
			}
			return counts;

		} catch (Exception e) {
			log.error("[Seat Hold 점유 수 배치 조회 오류] queryCount={}, error={}",
				keysList.size(), e.getMessage(), e);
			// 오류 시 모두 0 반환하여 검색 가용성 유지
			return keysList.stream().map(k -> 0).toList();
		}
	}

	/**
	 * 특정 객차의 구간에 Seat Hold된 좌석 ID 목록 조회
	 * <p>TrainCar Hold Index (Sorted Set)에서 만료되지 않은 멤버를 조회하여 검색 구간과 겹치는 seatId를 수집</p>
	 * <p>예) trainScheduleId=10, trainCarId=7인 객차에서 ["0-1", "1-2"] 구간과 겹치는 Seat Hold 좌석 ID를 반환한다.</p>
	 *
	 * @param trainScheduleId 열차 스케줄 ID
	 * @param trainCarId 객차 ID
	 * @param sections 검색 구간 목록 (예: ["0-1", "1-2"])
	 */
	public Set<Long> findSeatIdsOnHold(Long trainScheduleId, Long trainCarId, List<String> sections) {
		String trainCarHoldIndexKey = seatHoldKeyGenerator.generateTrainCarHoldIndexKey(trainScheduleId, trainCarId);
	    // 조회 단순성을 위해 Redis TIME 조회를 추가하지 않고 Java 서버 시각을 사용한다
	    // clock skew가 있어도 만료 경계에서의 일시적 노출 차이만 발생하며 실제 충돌 판정은 Lua(서버 시각)에서 보장된다
		long currentTime = System.currentTimeMillis() / 1000;

		Set<String> members = customStringRedisTemplate.opsForZSet()
			.rangeByScore(trainCarHoldIndexKey, currentTime, Double.MAX_VALUE);

		// hold가 없으면 조기 반환
		if (members == null || members.isEmpty()) {
			return Set.of();
		}

		Set<Long> seatIdsOnHold = new HashSet<>();
		for (String member : members) {
			// sections 파싱
			int colonIndex = getValidColonIndex(member);
			long seatId = Long.parseLong(member.substring(0, colonIndex));
			String section = member.substring(colonIndex + 1);

			// 겹치는 구간에 포함되는 경우 추가
			if (sections.contains(section)) {
				seatIdsOnHold.add(seatId);
			}
		}
		return seatIdsOnHold;
	}

	/**
	 * Release 스크립트 인자 배열 구성
	 *
	 * <p>ARGV 형식: [pendingBookingId, seatId, section1, section2, ...]</p>
	 *
	 * @param pendingBookingId 예약 ID
	 * @param seatId 좌석 ID
	 * @param sections 구간 목록
	 * @return Lua ARGV로 전달할 인자 배열
	 */
	private Object[] buildReleaseArgs(
		String pendingBookingId,
		Long seatId,
		List<String> sections
	) {
		Object[] args = new Object[sections.size() + 2];
		args[0] = pendingBookingId;                      // ARGV[1]: pendingBookingId
		args[1] = String.valueOf(seatId);                // ARGV[2]: seatId
		for (int i = 0; i < sections.size(); i++) {
			args[i + 2] = sections.get(i);               // ARGV[3...]: sections
		}
		return args;
	}

	/**
	 * Seat Hold 스크립트 인자 배열 구성
	 *
	 * <p>ARGV 형식: [ttl, pendingBookingId, seatId, section1, section2, ...]</p>
	 *
	 * @param pendingBookingId 예약 ID (Seat Hold Index에 추가할 값)
	 * @param seatId 좌석 ID (TrainCar Hold Index 멤버 생성용)
	 * @param sections 구간 목록 (예: ["0-1", "1-2", "2-3"])
	 * @param seatHoldTtlSeconds Seat Hold TTL (초)
	 * @return Lua ARGV로 전달할 인자 배열
	 */
	private Object[] buildSeatHoldArgs(
		String pendingBookingId,
		Long seatId,
		List<String> sections,
		long seatHoldTtlSeconds
	) {
		Object[] args = new Object[sections.size() + 3];
		args[0] = String.valueOf(seatHoldTtlSeconds);        // ARGV[1]: TTL
		args[1] = pendingBookingId;                      // ARGV[2]: pendingBookingId
		args[2] = String.valueOf(seatId);                // ARGV[3]: seatId
		for (int i = 0; i < sections.size(); i++) {
			args[i + 3] = sections.get(i);               // ARGV[4...]: sections
		}
		return args;
	}

	/**
	 * Seat Hold 좌석 수 조회 스크립트 인자 배열 구성
	 *
	 * <p>ARGV 형식: [section1, section2, ...]</p>
	 * <p>currentTime은 Lua 내부에서 redis.call("TIME")으로 직접 조회 (clock skew 방지)</p>
	 *
	 * @param sections 검색 구간 목록
	 * @return Lua ARGV로 전달할 인자 배열
	 */
	private static Object[] buildHoldSeatsCountArgs(List<String> sections) {
		Object[] args = new Object[sections.size()];
		for (int i = 0; i < sections.size(); i++) {
			args[i] = sections.get(i);
		}
		return args;
	}

	/**
	 * TrainCar Hold Index 멤버 문자열이 "seatId:section" 형식인지 검증하고 유효한 구분자 ':' 위치를 반환한다.
	 *
	 * @param member TrainCar Hold Index 멤버 문자열
	 * @return 유효한 ':' 인덱스
	 * @throws BusinessException 멤버 형식이 잘못된 경우
	 */
	private int getValidColonIndex(String member) {
		int colonIndex = member.indexOf(':');
		if (colonIndex <= 0 || colonIndex == member.length() - 1) {
			log.error("[TrainCar Hold Index 멤버 파싱 오류] 잘못된 포맷. member={}", member);
			throw new BusinessException(BookingError.SEAT_HOLD_SCRIPT_ERROR);
		}
		return colonIndex;
	}
}
