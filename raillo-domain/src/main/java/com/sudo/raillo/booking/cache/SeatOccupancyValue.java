package com.sudo.raillo.booking.cache;

public record SeatOccupancyValue(Type type, String id) {

	private static final String DELIMITER = ":";

	public enum Type {
		HOLD("H"),
		SOLD("B");

		private final String code;

		Type(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}

		static Type fromCode(String code) {
			for (Type type : values()) {
				if (type.code.equals(code)) {
					return type;
				}
			}
			throw new IllegalArgumentException("좌석 점유 유형이 아닙니다: " + code);
		}
	}

	public SeatOccupancyValue {
		if (type == null || id == null || id.isBlank()) {
			throw new IllegalArgumentException("좌석 점유 값에는 유형과 ID가 모두 필요합니다");
		}
	}

	public static SeatOccupancyValue hold(String reservationId) {
		return new SeatOccupancyValue(Type.HOLD, reservationId);
	}

	public static SeatOccupancyValue sold(String bookingId) {
		return new SeatOccupancyValue(Type.SOLD, bookingId);
	}

	public String serialize() {
		return type.code + DELIMITER + id;
	}

	public static SeatOccupancyValue parse(String value) {
		int delimiter = value == null ? -1 : value.indexOf(DELIMITER);
		if (delimiter <= 0 || delimiter == value.length() - 1) {
			throw new IllegalArgumentException("좌석 점유 값 형식이 아닙니다: " + value);
		}
		return new SeatOccupancyValue(
			Type.fromCode(value.substring(0, delimiter)),
			value.substring(delimiter + 1)
		);
	}

	public boolean isHold() {
		return type == Type.HOLD;
	}

	public boolean isSold() {
		return type == Type.SOLD;
	}

	public boolean isHeldBy(String reservationId) {
		return isHold() && id.equals(reservationId);
	}
}
