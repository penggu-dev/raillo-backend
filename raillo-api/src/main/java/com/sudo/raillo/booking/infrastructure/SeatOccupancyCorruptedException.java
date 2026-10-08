package com.sudo.raillo.booking.infrastructure;

/**
 * 좌석 점유 Hash에 좌석 점유 값이 아닌 값이 들어 있을 때 던진다.
 *
 * <p>스크립트 실행 실패나 응답 형식 불일치와 달리 재시도로 낫지 않는다. 호출자는 두 경우를 다른 에러 코드로 올려서
 * 재시도 정책과 알림이 둘을 구분할 수 있게 한다.</p>
 */
public class SeatOccupancyCorruptedException extends IllegalStateException {

	public SeatOccupancyCorruptedException(String message, Throwable cause) {
		super(message, cause);
	}
}
