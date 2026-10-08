package com.sudo.raillo.booking.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
	 *     좌석 점유 Hash에 좌석 점유 값이 아닌 데이터가 들어 있을 때({@code SEAT_OCCUPANCY_CORRUPTED})
	 */
	public SeatOccupancyResult occupy(SeatOccupancyCommand command) {
		SeatKeyLayout layout = SeatKeyLayout.of(command.trainScheduleId(), command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.ttlSeconds()));
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(command.reservationJson());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(layout.seatArgs());

		return runSeatScript(reservationCreateScript, "좌석 점유",
			layout.keys(reservationKey(command.trainScheduleId(), command.reservationId())), args,
			"reservationId=%s, trainScheduleId=%d, seatCount=%d"
				.formatted(command.reservationId(), command.trainScheduleId(), command.seats().size()));
	}

	/**
	 * 자기 예약 점유를 예매 점유로 바꾸고 예약 본문을 지운다. 다른 예약이나 예매가 점유한 구간이 있으면 아무것도 쓰지 않는다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때({@code SEAT_OCCUPANCY_SCRIPT_ERROR}),
	 *     좌석 점유 Hash에 좌석 점유 값이 아닌 데이터가 들어 있을 때({@code SEAT_OCCUPANCY_CORRUPTED})
	 */
	public SeatOccupancyResult confirmBooking(BookingOccupancyCommand command) {
		SeatKeyLayout layout = SeatKeyLayout.of(command.trainScheduleId(), command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.bookingId()));
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(layout.seatArgs());

		return runSeatScript(reservationBookingConfirmScript, "예매 점유 전환",
			layout.keys(reservationKey(command.trainScheduleId(), command.reservationId())), args,
			"reservationId=%s, bookingId=%d".formatted(command.reservationId(), command.bookingId()));
	}

	/**
	 * 결제 결과를 모르는 동안 자기 예약의 좌석 field를 붙잡는다. 다른 예약이나 예매가 점유한 구간이 있으면 아무것도 쓰지 않는다.
	 *
	 * @return 성공 또는 첫 번째 충돌 정보
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때({@code SEAT_OCCUPANCY_SCRIPT_ERROR}),
	 *     좌석 점유 값이 아닌 값을 만났을 때({@code SEAT_OCCUPANCY_CORRUPTED})
	 */
	public SeatOccupancyResult hold(SeatHoldCommand command) {
		SeatKeyLayout layout = SeatKeyLayout.of(command.trainScheduleId(), command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.keyExpireAtEpochSecond()));
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(layout.seatArgs());

		// 이 스크립트는 앞에 두는 키가 없어 객차 Hash가 KEYS[1]부터다
		return runSeatScript(reservationPaymentHoldScript, "결제 중 좌석 보호",
			layout.keys(), args,
			"reservationId=%s, trainScheduleId=%d, seatCount=%d"
				.formatted(command.reservationId(), command.trainScheduleId(), command.seats().size()));
	}

	/**
	 * 자기 예약 좌석 field의 만료를 보호 이전 상태로 되돌린다. 자기 것이 아닌 field는 건드리지 않는다.
	 *
	 * <p>{@link #runSeatScript}를 쓰지 않는다. 반환이 되돌린·삭제한 field 수이고 충돌 개념이 없어서
	 * 공통 골격에 분기를 넣는 쪽이 더 읽기 어렵다.</p>
	 *
	 * @return 되돌린 field 수와 삭제한 field 수
	 * @throws BusinessException 스크립트 실행이 실패했거나 응답이 계약과 다를 때
	 */
	public SeatHoldReleaseResult releaseHold(SeatHoldReleaseCommand command) {
		SeatKeyLayout layout = SeatKeyLayout.of(command.trainScheduleId(), command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(layout.seatArgs());

		String ctx = "reservationId=%s, trainScheduleId=%d"
			.formatted(command.reservationId(), command.trainScheduleId());
		try {
			// 예약 키와 주문 표시 키를 앞에 두므로 객차 Hash는 KEYS[3]부터다
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationPaymentReleaseScript,
				layout.keys(
					reservationKey(command.trainScheduleId(), command.reservationId()),
					ReservationCacheKey.reservationOrder(command.trainScheduleId(), command.reservationId())),
				args.toArray());
			SeatHoldReleaseResult result = SeatHoldReleaseResult.fromLuaResult(raw);
			log.info("[결제 중 좌석 보호 해제] {}, restored={}, deleted={}",
				ctx, result.restoredCount(), result.deletedCount());
			warnIfFieldsLeftBehind(command, result);
			return result;
		} catch (Exception e) {
			throw scriptFailure("결제 중 좌석 보호 해제", ctx, e, BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/**
	 * 자기 예약의 점유 field와 예약 본문을 지운다.
	 *
	 * <p>{@link #runSeatScript}를 쓰지 않는다. 반환이 해제 field 수이고 충돌·오염 개념이 없으며 실패 코드도
	 * {@code SEAT_OCCUPANCY_RELEASE_FAILED}로 달라서, 공통 골격에 분기를 넣는 쪽이 더 읽기 어렵다.</p>
	 */
	public void release(SeatReleaseCommand command) {
		SeatKeyLayout layout = SeatKeyLayout.of(command.trainScheduleId(), command.seats());

		List<String> args = new ArrayList<>();
		args.add(command.reservationId());
		args.add(String.valueOf(command.departureStopOrder()));
		args.add(String.valueOf(command.arrivalStopOrder()));
		args.addAll(layout.seatArgs());

		String ctx = "reservationId=%s, trainScheduleId=%d"
			.formatted(command.reservationId(), command.trainScheduleId());
		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationDeleteScript,
				layout.keys(reservationKey(command.trainScheduleId(), command.reservationId())), args.toArray());
			long released = ((Number)raw.get(0)).longValue();
			log.info("[좌석 점유 해제] {}, releasedFields={}", ctx, released);
		} catch (Exception e) {
			throw scriptFailure("좌석 점유 해제", ctx, e, BookingError.SEAT_OCCUPANCY_RELEASE_FAILED);
		}
	}

	/**
	 * 좌석 점유 스크립트의 공통 골격. 실행 → 결과 해석 → 성공·충돌 로그 → 오염·실행 실패 변환까지 한곳에서 처리한다.
	 *
	 * <p>스크립트마다 달라지는 것은 로그 접두사({@code label})와 식별자 문맥({@code ctx})뿐이다.
	 * 오염·실행 실패를 어떤 에러 코드로 올리는지는 여기 한 곳에만 적혀 있어야 한다. 메서드마다 적으면
	 * 계약이 바뀔 때 일부만 고쳐진다.</p>
	 */
	private SeatOccupancyResult runSeatScript(
		DefaultRedisScript<List> script, String label, List<String> keys, List<String> args, String ctx) {
		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(script, keys, args.toArray());
			SeatOccupancyResult result = SeatOccupancyResult.fromLuaResult(raw);

			if (result.success()) {
				log.info("[{} 성공] {}", label, ctx);
			} else {
				log.warn("[{} 충돌] {}, seatId={}, section={}, type={}", label, ctx,
					result.conflictSeatId(), result.conflictSectionIndex(), result.conflictType());
			}
			return result;

		} catch (SeatOccupancyCorruptedException e) {
			log.error("[{} 데이터 오염] {}, error={}", label, ctx, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_CORRUPTED);
		} catch (Exception e) {
			throw scriptFailure(label, ctx, e, BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/** 스크립트 실행 실패를 로그로 남기고 에러 코드로 바꾼다. 실패 코드가 메서드마다 달라 코드는 호출자가 정한다. */
	private static BusinessException scriptFailure(String label, String ctx, Exception e, BookingError error) {
		log.error("[{} 스크립트 오류] {}, error={}", label, ctx, e.getMessage(), e);
		return new BusinessException(error);
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

	private static String reservationKey(long trainScheduleId, String reservationId) {
		return ReservationCacheKey.reservation(trainScheduleId, reservationId);
	}

	/**
	 * 객차 Hash 키 목록과 좌석 ARGV 항목을 한 번의 순회로 함께 만든다.
	 *
	 * <p>좌석 ARGV 형식은 {@code seatId:carKeyIndex}이고 carKeyIndex는 {@link #carKeys} 안의 1부터 시작하는
	 * 순번이다. 키 순서와 순번을 각자 계산하면 두 계산이 조용히 갈라질 수 있어 한 객체가 둘 다 만든다.</p>
	 */
	private record SeatKeyLayout(List<String> carKeys, List<String> seatArgs) {

		private static SeatKeyLayout of(long trainScheduleId, List<SeatCar> seats) {
			Map<Long, Integer> carKeyIndexes = new LinkedHashMap<>();
			for (SeatCar seat : seats) {
				if (!carKeyIndexes.containsKey(seat.trainCarId())) {
					carKeyIndexes.put(seat.trainCarId(), carKeyIndexes.size() + 1);
				}
			}

			List<String> carKeys = carKeyIndexes.keySet().stream()
				.map(trainCarId -> ReservationCacheKey.carSeats(trainScheduleId, trainCarId))
				.toList();
			List<String> seatArgs = seats.stream()
				.map(seat -> seat.seatId() + ":" + carKeyIndexes.get(seat.trainCarId()))
				.toList();
			return new SeatKeyLayout(carKeys, seatArgs);
		}

		/**
		 * KEYS는 {@code leadingKeys} 다음에 객차 Hash가 처음 등장한 순서대로 중복 없이 온다.
		 *
		 * <p>좌석 ARGV의 carKeyIndex를 실제 KEYS 위치로 바꾸는 오프셋은 {@code leadingKeys.length}와 같다.
		 * 스크립트가 앞에 두는 키를 여기서 그대로 넘기므로 오프셋을 따로 세어 주석으로 적을 필요가 없다.
		 * 예전에는 스크립트마다 손으로 적은 오프셋이라, 틀려도 컴파일과 테스트는 통과하고 다른 객차 Hash에 썼다.</p>
		 */
		private List<String> keys(String... leadingKeys) {
			List<String> keys = new ArrayList<>(List.of(leadingKeys));
			keys.addAll(carKeys);
			return keys;
		}
	}
}
