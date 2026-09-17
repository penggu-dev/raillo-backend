package com.sudo.raillo.booking.infrastructure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;
import com.sudo.raillo.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 객차 좌석 점유 Hash를 Lua 스크립트로 다룬다. 키 형식은 {@link ReservationCacheKey}가 정한다.
 *
 * @see com.sudo.raillo.booking.infrastructure.config.RedisScriptConfig 스크립트 Bean 등록
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SeatOccupancyRepository {

	private final StringRedisTemplate stringRedisTemplate;
	private final DefaultRedisScript<List> reservationCreateScript;

	/**
	 * 요청 구간에 다른 점유가 없으면 좌석을 예약으로 점유하고 예약을 저장한다. 검사와 쓰기가 한 스크립트에서 원자적으로 끝난다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때
	 */
	public SeatOccupancyResult occupy(SeatOccupancyCommand command) {
		List<String> keys = buildKeys(command);
		Object[] args = buildArgs(command);

		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationCreateScript, keys, args);
			SeatOccupancyResult result = SeatOccupancyResult.fromLuaResult(raw);

			if (result.success()) {
				log.info("[좌석 점유 성공] reservationId={}, trainScheduleId={}, seatCount={}",
					command.reservationId(), command.trainScheduleId(), command.seats().size());
			} else {
				log.warn("[좌석 점유 충돌] reservationId={}, trainScheduleId={}, seatId={}, section={}, type={}",
					command.reservationId(), command.trainScheduleId(),
					result.conflictSeatId(), result.conflictSectionIndex(), result.conflictType());
			}
			return result;

		} catch (Exception e) {
			log.error("[좌석 점유 스크립트 오류] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/**
	 * KEYS[1]은 예약 키, KEYS[2..]는 객차 Hash를 처음 등장한 순서대로 중복 없이 나열한다.
	 */
	private static List<String> buildKeys(SeatOccupancyCommand command) {
		List<String> keys = new ArrayList<>();
		keys.add(ReservationCacheKey.reservation(command.trainScheduleId(), command.reservationId()));
		for (long trainCarId : distinctTrainCarIds(command.seats())) {
			keys.add(ReservationCacheKey.carSeats(command.trainScheduleId(), trainCarId));
		}
		return keys;
	}

	/**
	 * ARGV[7..]의 좌석 항목은 {@code seatId:carKeyIndex}이며 carKeyIndex는 KEYS[2..] 안의 1부터 시작하는 순번이다.
	 */
	private static Object[] buildArgs(SeatOccupancyCommand command) {
		List<Long> trainCarIds = distinctTrainCarIds(command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.ttlSeconds()));
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(command.reservationJson());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		for (SeatCar seat : command.seats()) {
			int carKeyIndex = trainCarIds.indexOf(seat.trainCarId()) + 1;
			args.add(seat.seatId() + ":" + carKeyIndex);
		}
		return args.toArray();
	}

	private static List<Long> distinctTrainCarIds(List<SeatCar> seats) {
		return seats.stream().map(SeatCar::trainCarId).distinct().toList();
	}
}
