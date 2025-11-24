package com.sudo.raillo.booking.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.domain.ProvisionalBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisBookingService {

	private final RedissonClient redissonClient;
	private final RedisTemplate<String, ProvisionalBooking> redisTemplate;

	private static final String BOOKING_KEY_PREFIX = "booking:provisional:";
	private static final String SEAT_LOCK_PREFIX = "seat:lock:";
	private static final int TTL_MINUTES = 10;
	private static final int LOCK_WAIT_SECONDS = 3; // 조정 필요
	private static final int LOCK_LEASE_SECONDS = 610; // 10분 10초

	/**
	 * 임시 예약 생성 (분산 락 포함)
	 */
	public String createProvisionalBooking(
		String memberId,
		ReservationCreateRequest request,
		Integer totalFare
	) {
		String bookingId = UUID.randomUUID().toString(); // TODO : Generator 필요
		List<RLock> acquiredLocks = new ArrayList<>();

		try {
			// 1. 모든 좌석에 대해 분산 락 획득
			for (Long seatId : request.seatIds()) {
				String lockKey = buildSeatLockKey(
					request.trainScheduleId(),
					request.departureStationId(),
					request.arrivalStationId(),
					seatId
				);
				RLock lock = redissonClient.getLock(lockKey);

				boolean acquired = lock.tryLock(
					LOCK_WAIT_SECONDS,
					LOCK_LEASE_SECONDS,
					TimeUnit.SECONDS
				);

				if (!acquired) {
					log.warn("좌석 락 획득 실패 - trainScheduleId: {}, seatId: {}",
						request.trainScheduleId(), seatId);
					throw new BusinessException(BookingError.SEAT_ALREADY_RESERVED);
				}

				acquiredLocks.add(lock);
			}

			// 2. 임시 예약 데이터 생성 및 저장
			ProvisionalBooking provisional = ProvisionalBooking.from(
				bookingId,
				memberId,
				request,
				totalFare
			);

			String bookingKey = BOOKING_KEY_PREFIX + bookingId;
			redisTemplate.opsForValue().set(
				bookingKey,
				provisional,
				TTL_MINUTES,
				TimeUnit.MINUTES
			);

			log.info("임시 예약 생성 완료 - bookingId: {}, memberId: {}, trainScheduleId: {}",
				bookingId, memberId, request.trainScheduleId());

			return bookingId;

		} catch (InterruptedException e) {
			releaseAllLocks(acquiredLocks);
			Thread.currentThread().interrupt();
			throw new BusinessException(BookingError.SEAT_RESERVATION_FAILED);
		} catch (Exception e) {
			releaseAllLocks(acquiredLocks);
			throw e;
		}
	}

	/**
	 * 임시 예약 조회
	 */
	public Optional<ProvisionalBooking> getProvisionalBooking(String bookingId) {
		String key = BOOKING_KEY_PREFIX + bookingId;
		ProvisionalBooking booking = redisTemplate.opsForValue().get(key);
		return Optional.ofNullable(booking);
	}

	/**
	 * 임시 예약 삭제
	 */
	public void deleteProvisionalBooking(String bookingId) {
		ProvisionalBooking booking = getProvisionalBooking(bookingId)
			.orElse(null);

		if (booking == null) {
			log.debug("삭제할 임시 예약이 없음 - bookingId: {}", bookingId);
			return;
		}

		// 좌석 락 해제
		for (Long seatId : booking.getSeatIds()) {
			String lockKey = buildSeatLockKey(
				booking.getTrainScheduleId(),
				booking.getDepartureStationId(),
				booking.getArrivalStationId(),
				seatId
			);
			RLock lock = redissonClient.getLock(lockKey);

			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}

		// 예약 데이터 삭제
		String bookingKey = BOOKING_KEY_PREFIX + bookingId;
		redisTemplate.delete(bookingKey);

		log.info("임시 예약 삭제 완료 - bookingId: {}", bookingId);
	}

	/**
	 * 좌석 락 키 생성
	 * 형식: seat:lock:{trainScheduleId}:{departureStationId}:{arrivalStationId}:{seatId}
	 */
	private String buildSeatLockKey(
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId,
		Long seatId
	) {
		return String.format("%s%d:%d:%d:%d",
			SEAT_LOCK_PREFIX,
			trainScheduleId,
			departureStationId,
			arrivalStationId,
			seatId
		);
	}

	private void releaseAllLocks(List<RLock> locks) {
		for (RLock lock : locks) {
			try {
				if (lock.isHeldByCurrentThread()) {
					lock.unlock();
				}
			} catch (Exception e) {
				log.error("락 해제 실패", e);
			}
		}
	}
}
