package com.sudo.raillo.booking.infrastructure;

import java.util.List;

/**
 * `Booking` 점유 해제 결과.
 *
 * @param releasedCount 지운 field 수
 * @param foreignCount 값이 있었지만 이 `Booking`의 것이 아니어서 건너뛴 field 수
 */
public record BookingSeatReleaseResult(int releasedCount, int foreignCount) {

	/** Lua 반환값 {@code {released, foreign}}을 파싱한다. */
	public static BookingSeatReleaseResult fromLuaResult(List<Object> result) {
		if (result == null || result.size() < 2) {
			throw new IllegalStateException("예매 점유 해제 응답 형식이 아닙니다: " + result);
		}
		return new BookingSeatReleaseResult(((Long)result.get(0)).intValue(), ((Long)result.get(1)).intValue());
	}
}
