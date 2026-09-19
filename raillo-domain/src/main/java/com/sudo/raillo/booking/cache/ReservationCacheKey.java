package com.sudo.raillo.booking.cache;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 예약과 좌석 점유 상태의 Redis 키
 *
 * <pre>
 * {schedule:{trainScheduleId}}:car:{trainCarId}:seats     Hash    field {seatId}:{sectionIndex} → SeatOccupancyValue
 * {schedule:{trainScheduleId}}:reservation:{reservationId} String  Reservation JSON
 * member:{memberNo}:reservations                          Hash    field {reservationId} → trainScheduleId
 * </pre>
 *
 * <p>구간(section)은 정차 순서 i에서 i+1로 가는 한 칸이며 출발 stopOrder d, 도착 stopOrder a인 요청은 d..a-1 구간을 점유</p>
 */
public final class ReservationCacheKey {

	private static final String SCHEDULE_PREFIX = "{schedule:%d}:";

	private static final String CAR_SEATS_KEY = SCHEDULE_PREFIX + "car:%d:seats";
	private static final String RESERVATION_KEY = SCHEDULE_PREFIX + "reservation:%s";
	private static final String MEMBER_RESERVATIONS_KEY = "member:%s:reservations";

	private static final String SEAT_FIELD = "%d:%d";

	private ReservationCacheKey() {
	}

	/** 객차 하나의 좌석 점유 상태를 담는 Hash 키 */
	public static String carSeats(long trainScheduleId, long trainCarId) {
		return CAR_SEATS_KEY.formatted(trainScheduleId, trainCarId);
	}

	/** 좌석 점유 Hash의 field. 좌석 하나의 구간 하나를 가리킨다. */
	public static String seatField(long seatId, int sectionIndex) {
		return SEAT_FIELD.formatted(seatId, sectionIndex);
	}

	public static String reservation(long trainScheduleId, String reservationId) {
		return RESERVATION_KEY.formatted(trainScheduleId, reservationId);
	}

	public static String memberReservations(String memberNo) {
		return MEMBER_RESERVATIONS_KEY.formatted(memberNo);
	}

	/**
	 * 출발·도착 stopOrder가 점유하는 구간 index 목록. {@code [departure, arrival)} 범위다.
	 *
	 * @throws IllegalArgumentException 출발이 도착보다 앞서지 않을 때
	 */
	public static List<Integer> sectionIndices(int departureStopOrder, int arrivalStopOrder) {
		if (departureStopOrder >= arrivalStopOrder) {
			throw new IllegalArgumentException(
				"출발 stopOrder는 도착보다 앞서야 합니다: " + departureStopOrder + " -> " + arrivalStopOrder);
		}
		return IntStream.range(departureStopOrder, arrivalStopOrder).boxed().toList();
	}
}
