package com.sudo.raillo.train.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.train.domain.type.SeatDirection;
import com.sudo.raillo.train.domain.type.SeatType;

import lombok.Getter;

/**
 * 좌석 정보 조회용 Projection
 */
@Getter
public class SeatProjection {

	private final Long seatId;
	private final String seatNumber;
	private final SeatType seatType;
	private final String directionCode;
	private final boolean isBooked;
	private final String specialMessage;

	@QueryProjection
	public SeatProjection(
		Long seatId,
		String seatNumber,
		SeatType seatType,
		String directionCode,
		boolean isBooked,
		String specialMessage
	) {
		this.seatId = seatId;
		this.seatNumber = seatNumber;
		this.seatType = seatType;
		this.directionCode = directionCode;
		this.isBooked = isBooked;
		this.specialMessage = specialMessage;
	}

	/**
	 * 예약 가능 여부 반환
	 */
	public boolean isAvailable() {
		return !isBooked;
	}

	/**
	 * 좌석 방향 반환
	 */
	public SeatDirection getSeatDirection() {
		return SeatDirection.fromCode(directionCode);
	}
}
