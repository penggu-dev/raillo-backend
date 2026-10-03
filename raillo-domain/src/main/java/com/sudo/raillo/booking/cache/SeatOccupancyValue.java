package com.sudo.raillo.booking.cache;

/**
 * 좌석 점유 Hash field의 값. {@code R:{reservationId}}(예약) 또는 {@code B:{bookingId}}(예매)다.
 *
 * <p>예약 field는 예약 TTL만큼 HEXPIRE로 만료되고, 예매 field는 만료가 없다.</p>
 *
 * <p>예외가 하나 있다. 결제 결과를 모르는 동안에는 {@code reservation_payment_hold.lua}가 예약 field의 만료를 없애고,
 * 결과가 실패로 확정되면 {@code reservation_payment_release.lua}가 되돌린다. 그 사이의 예약 field에는 만료가 없으므로
 * 만료가 없다는 사실만으로 예매 field라고 판단하면 안 된다. 자세한 규칙은
 * {@code docs/reservation-cache-schema.md} 5장에 있다.</p>
 */
public record SeatOccupancyValue(Type type, String id) {

	private static final String DELIMITER = ":";

	public enum Type {
		RESERVED("R"),
		BOOKED("B");

		private final String code;

		Type(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}

		public static Type fromCode(String code) {
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

	public static SeatOccupancyValue reserved(String reservationId) {
		return new SeatOccupancyValue(Type.RESERVED, reservationId);
	}

	public static SeatOccupancyValue booked(String bookingId) {
		return new SeatOccupancyValue(Type.BOOKED, bookingId);
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

	public boolean isReserved() {
		return type == Type.RESERVED;
	}

	public boolean isBooked() {
		return type == Type.BOOKED;
	}

	public boolean isReservedBy(String reservationId) {
		return isReserved() && id.equals(reservationId);
	}
}
