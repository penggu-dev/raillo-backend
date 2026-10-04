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
			layout.keysWithReservation(command.trainScheduleId(), command.reservationId()), args,
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
			layout.keysWithReservation(command.trainScheduleId(), command.reservationId()), args,
			"reservationId=%s, bookingId=%d".formatted(command.reservationId(), command.bookingId()));
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

		try {
			@SuppressWarnings("unchecked")
			List<Object> raw = stringRedisTemplate.execute(reservationDeleteScript,
				layout.keysWithReservation(command.trainScheduleId(), command.reservationId()), args.toArray());
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
			log.error("[{} 스크립트 오류] {}, error={}", label, ctx, e.getMessage(), e);
			throw new BusinessException(BookingError.SEAT_OCCUPANCY_SCRIPT_ERROR);
		}
	}

	/**
	 * 객차 Hash 키 목록과 좌석 ARGV 항목을 한 번의 순회로 함께 만든다.
	 *
	 * <p>좌석 ARGV 형식은 {@code seatId:carKeyIndex}이고 carKeyIndex는 {@link #carKeys} 안의 1부터 시작하는 순번이다.
	 * 키 순서와 순번을 각자 계산하면 두 계산이 조용히 갈라질 수 있어 한 객체가 둘 다 만든다.
	 *
	 * <p>이 순번을 실제 KEYS 위치로 바꾸는 오프셋은 스크립트마다 다르고 호출자 책임이다. 객차 Hash 블록이
	 * KEYS 몇 번째부터 시작하는지가 스크립트마다 다르기 때문이다. 새 스크립트에 쓸 때는 그 스크립트의
	 * KEYS 배치를 먼저 확인해야 한다. 틀려도 컴파일과 테스트는 통과하고 다른 객차 Hash에 쓴다.</p>
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

		/** KEYS[1]은 예약 키, KEYS[2..]는 객차 Hash를 처음 등장한 순서대로 중복 없이 나열한다. */
		private List<String> keysWithReservation(long trainScheduleId, String reservationId) {
			List<String> keys = new ArrayList<>();
			keys.add(ReservationCacheKey.reservation(trainScheduleId, reservationId));
			keys.addAll(carKeys);
			return keys;
		}
	}
}
