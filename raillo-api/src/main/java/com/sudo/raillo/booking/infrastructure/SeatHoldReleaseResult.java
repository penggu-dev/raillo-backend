package com.sudo.raillo.booking.infrastructure;

import java.util.List;

/**
 * 좌석 보호 해제 결과. 충돌이라는 개념이 없고 자기 field에 무슨 일이 있었는지만 알린다.
 *
 * @param restoredCount 만료를 되돌린 field 수
 * @param deletedCount 되돌릴 기한이 없어 삭제한 field 수
 */
public record SeatHoldReleaseResult(int restoredCount, int deletedCount) {

	/** Lua 반환값 {@code {restored, deleted}}를 파싱한다. */
	public static SeatHoldReleaseResult fromLuaResult(List<Object> result) {
		if (result == null || result.size() < 2) {
			throw new IllegalStateException("보호 해제 응답 형식이 아닙니다: " + result);
		}
		return new SeatHoldReleaseResult(((Long)result.get(0)).intValue(), ((Long)result.get(1)).intValue());
	}
}
