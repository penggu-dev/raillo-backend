package com.sudo.raillo.support.helper;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;

import lombok.RequiredArgsConstructor;

/**
 * 예약을 좌석 점유 없이 Redis에 바로 넣는다. 예약 본문과 회원 인덱스만 만들며, 점유가 필요하면
 * {@link SeatOccupancyTestHelper}를 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ReservationTestHelper {

	private final StringRedisTemplate stringRedisTemplate;
	private final RedisJsonConverter redisJsonConverter;
	private final ReservationRedisRepository reservationRedisRepository;

	public Reservation save(Reservation reservation) {
		return save(reservation, Duration.ofMinutes(10));
	}

	public Reservation save(Reservation reservation, Duration ttl) {
		stringRedisTemplate.opsForValue().set(
			ReservationCacheKey.reservation(reservation.trainScheduleId(), reservation.reservationId()),
			redisJsonConverter.toJson(reservation),
			ttl);
		reservationRedisRepository.indexForMember(
			reservation.memberNo(), reservation.reservationId(), reservation.trainScheduleId(), ttl);
		return reservation;
	}

	/** 인덱스 없이 본문만 저장한다. 인덱스 누락 상황을 만들 때 쓴다. */
	public Reservation saveBodyOnly(Reservation reservation) {
		stringRedisTemplate.opsForValue().set(
			ReservationCacheKey.reservation(reservation.trainScheduleId(), reservation.reservationId()),
			redisJsonConverter.toJson(reservation),
			Duration.ofMinutes(10));
		return reservation;
	}

	/** 본문 없이 인덱스만 등록한다. 본문이 먼저 만료된 상황을 만들 때 쓴다. */
	public void saveIndexOnly(Reservation reservation) {
		reservationRedisRepository.indexForMember(
			reservation.memberNo(), reservation.reservationId(), reservation.trainScheduleId(), Duration.ofMinutes(10));
	}
}
