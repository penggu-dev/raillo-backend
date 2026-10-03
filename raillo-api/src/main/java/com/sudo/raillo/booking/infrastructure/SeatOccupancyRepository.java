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
	private final DefaultRedisScript<List> reservationPaymentHoldScript;
	private final DefaultRedisScript<List> reservationPaymentReleaseScript;

	/**
	 * 요청 구간에 다른 점유가 없으면 좌석을 예약으로 점유하고 예약을 저장한다. 검사와 쓰기가 한 스크립트에서 원자적으로 끝난다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때({@code SEAT_OCCUPANCY_SCRIPT_ERROR}),
	 *     좌석 점유 값이 아닌 값을 만났을 때({@code SEAT_OCCUPANCY_CORRUPTED})
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
	 *     좌석 점유 값이 아닌 값을 만났을 때({@code SEAT_OCCUPANCY_CORRUPTED})
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
	 * 결제 결과를 모르는 동안 자기 예약의 좌석 field를 붙잡는다. 다른 예약이나 예매가 점유한 구간이 있으면 아무것도 쓰지 않는다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때({@code SEAT_OCCUPANCY_SCRIPT_ERROR}),
	 *     좌석 점유 값이 아닌 값을 만났을 때({@code SEAT_OCCUPANCY_CORRUPTED})
	 */
	public SeatOccupancyResult hold(SeatHoldCommand command) {
		// 이 스크립트는 예약 키를 쓰지 않아 객차 Hash가 KEYS[1]부터다. carKeyIndex 오프셋은 0
		List<String> keys = new ArrayList<>();
		for (long trainCarId : distinctTrainCarIds(command.seats())) {
			keys.add(ReservationCacheKey.carSeats(command.trainScheduleId(), trainCarId));
		}

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(buildSeatArgs(command.seats()));

		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationPaymentHoldScript, keys, args.toArray());
			SeatOccupancyResult result = SeatOccupancyResult.fromLuaResult(raw);
			if (result.success()) {
				log.info("[결제 중 좌석 보호 성공] reservationId={}, trainScheduleId={}, seatCount={}",
					command.reservationId(), command.trainScheduleId(), command.seats().size());
			} else {
				log.warn("[결제 중 좌석 보호 충돌] reservationId={}, trainScheduleId={}, seatId={}, section={}, type={}",
					command.reservationId(), command.trainScheduleId(),
					result.conflictSeatId(), result.conflictSectionIndex(), result.conflictType());
			}
			return result;
		} catch (SeatOccupancyCorruptedException e) {
			log.error("[결제 중 좌석 보호 데이터 오염] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED);
		} catch (Exception e) {
			log.error("[결제 중 좌석 보호 스크립트 오류] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/**
	 * 자기 예약 좌석 field의 만료를 보호 이전 상태로 되돌린다. 자기 것이 아닌 field는 건드리지 않는다.
	 *
	 * @return 되돌린 field 수와 삭제한 field 수
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때
	 */
	public SeatHoldReleaseResult releaseHold(SeatHoldReleaseCommand command) {
		// 이 스크립트는 예약 키와 주문 표시 키를 앞에 두므로 객차 Hash가 KEYS[3]부터다. carKeyIndex 오프셋은 2
		List<String> keys = new ArrayList<>();
		keys.add(ReservationCacheKey.reservation(command.trainScheduleId(), command.reservationId()));
		keys.add(ReservationCacheKey.reservationOrder(command.trainScheduleId(), command.reservationId()));
		for (long trainCarId : distinctTrainCarIds(command.seats())) {
			keys.add(ReservationCacheKey.carSeats(command.trainScheduleId(), trainCarId));
		}

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(buildSeatArgs(command.seats()));

		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationPaymentReleaseScript, keys, args.toArray());
			SeatHoldReleaseResult result = SeatHoldReleaseResult.fromLuaResult(raw);
			log.info("[결제 중 좌석 보호 해제] reservationId={}, trainScheduleId={}, restored={}, deleted={}",
				command.reservationId(), command.trainScheduleId(), result.restoredCount(), result.deletedCount());
			warnIfFieldsLeftBehind(command, result);
			return result;
		} catch (Exception e) {
			log.error("[결제 중 좌석 보호 해제 스크립트 오류] reservationId={}, trainScheduleId={}, error={}",
				command.reservationId(), command.trainScheduleId(), e.getMessage(), e);
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

	/**
	 * 보호 해제가 손대지 못한 field가 있으면 경고로 남긴다.
	 *
	 * <p>{@code hold}가 HPERSIST로 만료를 없앴기 때문에, 해제가 건너뛴 field는 만료도 주인도 없는 상태로 남아
	 * 객차 키의 운행일 만료까지 팔리지 않는다. 실패로 볼 수는 없다. 같은 사이 다른 스레드가 예매로 전환한 field도
	 * 정상적으로 건너뛰기 때문이다. 그래서 던지지 않고 찾을 수 있는 신호만 남긴다.</p>
	 */
	private static void warnIfFieldsLeftBehind(SeatHoldReleaseCommand command, SeatHoldReleaseResult result) {
		int sections = command.arrivalStopOrder() - command.departureStopOrder();
		int expected = command.seats().size() * sections;
		int handled = result.restoredCount() + result.deletedCount();
		if (handled < expected) {
			log.warn("[결제 중 좌석 보호 해제 - 손대지 못한 field] reservationId={}, trainScheduleId={}, "
					+ "expected={}, handled={}, leftBehind={} (예매 전환이면 정상, 오염이면 운행일까지 좌석이 묶인다)",
				command.reservationId(), command.trainScheduleId(), expected, handled, expected - handled);
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
