package com.sudo.raillo.booking.redis;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import com.sudo.raillo.booking.domain.type.PassengerType;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@RedisHash(value = "seat-reservation", timeToLive = 600)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoldingSeatReservation {

	@Id
	private Long id;
	private Long trainScheduleId;
	private Long seatId;
	private Long reservationId;
	private PassengerType passengerType;

	public boolean isStanding() {
		return seatId == null;
	}
}
