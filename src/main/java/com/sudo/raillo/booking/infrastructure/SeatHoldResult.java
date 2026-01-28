package com.sudo.raillo.booking.infrastructure;

import java.util.List;

/**
 * Lua 스크립트 실행 결과를 담는 DTO
 */
public record SeatHoldResult(
	boolean success,
	String status,
	String conflictSection
) {

	private static final long SUCCESS_CODE = 1L;
	private static final String CONFLICT_WITH_SOLD = "CONFLICT_WITH_SOLD";
	private static final String CONFLICT_WITH_HOLD = "CONFLICT_WITH_HOLD";

	/**
	 * Lua 스크립트 반환값을 파싱하여 SeatHoldResult 생성
	 * Lua 반환: {1, "HOLD_SUCCESS"} 또는 {0, "CONFLICT_WITH_SOLD", "1-2"}
	 */
	public static SeatHoldResult fromLuaResult(List<Object> result) {
		if (result == null || result.isEmpty()) {
			return failure("INVALID_RESPONSE", null);
		}

		long code = (Long) result.get(0);
		String status = (String) result.get(1);
		String conflictSection = result.size() > 2 ? (String) result.get(2) : null;

		boolean success = code == SUCCESS_CODE;
		return new SeatHoldResult(success, status, conflictSection);
	}

	public static SeatHoldResult failure(String status, String conflictSection) {
		return new SeatHoldResult(false, status, conflictSection);
	}

	public boolean isConflictWithSold() {
		return CONFLICT_WITH_SOLD.equals(status);
	}

	public boolean isConflictWithHold() {
		return CONFLICT_WITH_HOLD.equals(status);
	}
}

