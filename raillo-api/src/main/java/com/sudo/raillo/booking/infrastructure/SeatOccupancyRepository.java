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
	private final DefaultRedisScript<List> reservationDeleteScript;
	private final DefaultRedisScript<List> reservationBookingConfirmScript;

	/**
	 * 요청 구간에 다른 점유가 없으면 좌석을 예약으로 점유하고 예약을 저장한다. 검사와 쓰기가 한 스크립트에서 원자적으로 끝난다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때({@code SEAT_OCCUPANCY_SCRIPT_ERROR}),
	 *     좌석 점유 Hash에 좌석 점유 값이 아닌 데이터가 들어 있을 때({@code SEAT_OCCUPANCY_CORRUPTED})
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

		} catch (SeatOccupancyCorruptedException e) {
			log.error("[좌석 점유 데이터 오염] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED);
		} catch (Exception e) {
			log.error("[좌석 점유 스크립트 오류] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/**
	 * 자기 예약 점유를 예매 점유로 바꾸고 예약 본문을 지운다. 다른 예약이나 예매가 점유한 구간이 있으면 아무것도 쓰지 않는다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때({@code SEAT_OCCUPANCY_SCRIPT_ERROR}),
	 *     좌석 점유 Hash에 좌석 점유 값이 아닌 데이터가 들어 있을 때({@code SEAT_OCCUPANCY_CORRUPTED})
	 */
	public SeatOccupancyResult confirmBooking(BookingOccupancyCommand command) {
		List<String> keys = buildKeys(command.trainScheduleId(), command.reservationId(), command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.bookingId()));
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(buildSeatArgs(command.seats()));

		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationBookingConfirmScript, keys, args.toArray());
			SeatOccupancyResult result = SeatOccupancyResult.fromLuaResult(raw);
			if (result.success()) {
				log.info("[예매 점유 전환 성공] reservationId={}, bookingId={}",
					command.reservationId(), command.bookingId());
			} else {
				log.warn("[예매 점유 전환 충돌] reservationId={}, bookingId={}, seatId={}, section={}, type={}",
					command.reservationId(), command.bookingId(),
					result.conflictSeatId(), result.conflictSectionIndex(), result.conflictType());
			}
			return result;
		} catch (SeatOccupancyCorruptedException e) {
			log.error("[예매 점유 전환 데이터 오염] reservationId={}, bookingId={}, error={}",
				command.reservationId(), command.bookingId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED);
		} catch (Exception e) {
			log.error("[예매 점유 전환 스크립트 오류] reservationId={}, bookingId={}, error={}",
				command.reservationId(), command.bookingId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/**
	 * KEYS[1]은 예약 키, KEYS[2..]는 객차 Hash를 처음 등장한 순서대로 중복 없이 나열한다.
	 */
	public void release(SeatReleaseCommand command) {
		List<String> keys = buildKeys(command.trainScheduleId(), command.reservationId(), command.seats());
		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(buildSeatArgs(command.seats()));

		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationDeleteScript, keys, args.toArray());
			long released = ((Number)raw.get(0)).longValue();
			log.info("[좌석 점유 해제] reservationId={}, trainScheduleId={}, releasedFields={}",
				command.reservationId(), command.trainScheduleId(), released);
		} catch (Exception e) {
			log.error("[좌석 점유 해제 스크립트 오류] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_RELEASE_FAILED);
		}
	}

	private static List<String> buildKeys(SeatOccupancyCommand command) {
		return buildKeys(command.trainScheduleId(), command.reservationId(), command.seats());
	}

	/**
	 * KEYS[1]은 예약 키, KEYS[2..]는 객차 Hash를 처음 등장한 순서대로 중복 없이 나열한다.
	 */
	private static List<String> buildKeys(long trainScheduleId, String reservationId, List<SeatCar> seats) {
		List<String> keys = new ArrayList<>();
		keys.add(ReservationCacheKey.reservation(trainScheduleId, reservationId));
		for (long trainCarId : distinctTrainCarIds(seats)) {
			keys.add(ReservationCacheKey.carSeats(trainScheduleId, trainCarId));
		}
		return keys;
	}

	/**
	 * ARGV[7..]의 좌석 항목은 {@code seatId:carKeyIndex}이며 carKeyIndex는 KEYS[2..] 안의 1부터 시작하는 순번이다.
	 */
	private static Object[] buildArgs(SeatOccupancyCommand command) {
		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.ttlSeconds()));
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(command.reservationJson());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(buildSeatArgs(command.seats()));
		return args.toArray();
	}

	/**
	 * 좌석 ARGV 항목을 만든다. 형식은 {@code seatId:carKeyIndex}이며 carKeyIndex는
	 * {@link #distinctTrainCarIds} 순서(처음 등장 순) 안의 **1부터 시작하는 순번**이다.
	 *
	 * <p>이 순번을 실제 KEYS 위치로 바꾸는 오프셋은 스크립트마다 다르고 호출자 책임이다.
	 * 객차 Hash 블록이 KEYS 몇 번째부터 시작하는지가 스크립트마다 다르기 때문이다.
	 * 새 스크립트에 이 헬퍼를 쓸 때는 그 스크립트의 KEYS 배치를 먼저 확인해야 한다. 틀려도
	 * 컴파일과 테스트는 통과하고 다른 객차 Hash에 쓴다.</p>
	 */
	private static List<String> buildSeatArgs(List<SeatCar> seats) {
		List<Long> trainCarIds = distinctTrainCarIds(seats);
		return seats.stream()
			.map(seat -> seat.seatId() + ":" + (trainCarIds.indexOf(seat.trainCarId()) + 1))
			.toList();
	}

	private static List<Long> distinctTrainCarIds(List<SeatCar> seats) {
		return seats.stream().map(SeatCar::trainCarId).distinct().toList();
	}
}
