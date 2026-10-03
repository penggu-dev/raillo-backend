package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.cache.SeatOccupancyValue;

/**
 * 예약 생성과 예매 점유 전환, 두 좌석 점유 스크립트의 결과.
 *
 * @param conflictSeatId 충돌한 좌석 ID. 성공이면 {@code null}
 * @param conflictSectionIndex 충돌한 구간 index. 성공이면 {@code null}
 * @param conflictType 충돌한 점유 유형. 성공이면 {@code null}
 */
public record SeatOccupancyResult(
	boolean success,
	Long conflictSeatId,
	Integer conflictSectionIndex,
	SeatOccupancyValue.Type conflictType
) {

	private static final long SUCCESS_CODE = 1L;
	private static final SeatOccupancyResult SUCCESS = new SeatOccupancyResult(true, null, null, null);

	public static SeatOccupancyResult succeeded() {
		return SUCCESS;
	}

	/**
	 * Lua 반환값 {@code {1}} 또는 {@code {0, seatId, sectionIndex, "R"|"B"}}를 파싱한다.
	 * 점유 유형 자리에는 {@code "R"}이나 {@code "B"} 외에 알 수 없는 값(예: {@code "X"})도 올 수 있다.
	 *
	 * <p>두 실패를 구분해서 던진다. 응답 형식이 계약과 다른 것은 인프라 문제이므로
	 * {@link IllegalStateException}, 점유 유형이 아닌 값은 Redis 데이터 오염이므로
	 * {@link SeatOccupancyCorruptedException}이다. 전자는 재시도가 의미 있고 후자는 없다.</p>
	 *
	 * @throws IllegalStateException 반환 형식이 계약과 다를 때
	 * @throws SeatOccupancyCorruptedException 좌석 점유 유형이 아닌 값이 들어 있을 때
	 */
	public static SeatOccupancyResult fromLuaResult(List<Object> result) {
		if (result == null || result.isEmpty()) {
			throw new IllegalStateException("스크립트 응답이 비어 있습니다");
		}
		if ((Long)result.get(0) == SUCCESS_CODE) {
			return SUCCESS;
		}
		if (result.size() < 4) {
			throw new IllegalStateException("충돌 응답 형식이 아닙니다: " + result);
		}

		SeatOccupancyValue.Type conflictType;
		try {
			conflictType = SeatOccupancyValue.Type.fromCode((String)result.get(3));
		} catch (IllegalArgumentException e) {
			throw new SeatOccupancyCorruptedException("좌석 점유 유형이 아닌 값이 들어 있습니다: " + result, e);
		}

		return new SeatOccupancyResult(
			false,
			Long.parseLong((String)result.get(1)),
			((Long)result.get(2)).intValue(),
			conflictType
		);
	}

	public boolean isConflictWithReservation() {
		return conflictType == SeatOccupancyValue.Type.RESERVED;
	}

	public boolean isConflictWithBooking() {
		return conflictType == SeatOccupancyValue.Type.BOOKED;
	}
}
