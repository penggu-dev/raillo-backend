package com.sudo.raillo.booking.domain;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis에 저장되는 좌석 락 정보
 * 좌석 예약 중복을 방지하기 위한 임시 락
 */
@RedisHash(value = "lockedSeat", timeToLive = 10) // 10초 TTL
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LockedSeat {

	/**
	 * 복합 키: trainScheduleId:seatId:departureStationId:arrivalStationId
	 * 예: "527:18736:50:53"
	 */
	@Id
	private String lockKey;

	/**
	 * 열차 스케줄 ID
	 */
	@Indexed
	private Long trainScheduleId;

	/**
	 * 좌석 ID
	 */
	@Indexed
	private Long seatId;

	/**
	 * 출발역 ID
	 */
	private Long departureStationId;

	/**
	 * 도착역 ID
	 */
	private Long arrivalStationId;

	/**
	 * 락을 획득한 회원 번호
	 */
	private String memberNo;

	/**
	 * 락 생성 시간
	 */
	private LocalDateTime lockedAt;

	/**
	 * 락 키 생성 메서드
	 */
	public static String generateLockKey(
		Long trainScheduleId,
		Long seatId,
		Long departureStationId,
		Long arrivalStationId
	) {
		return String.format("%d:%d:%d:%d", trainScheduleId, seatId, departureStationId, arrivalStationId);
	}

	/**
	 * LockedSeat 생성 팩토리 메서드
	 */
	public static LockedSeat create(
		Long trainScheduleId,
		Long seatId,
		Long departureStationId,
		Long arrivalStationId,
		String memberNo
	) {
		String lockKey = generateLockKey(trainScheduleId, seatId, departureStationId, arrivalStationId);

		return LockedSeat.builder()
			.lockKey(lockKey)
			.trainScheduleId(trainScheduleId)
			.seatId(seatId)
			.departureStationId(departureStationId)
			.arrivalStationId(arrivalStationId)
			.memberNo(memberNo)
			.lockedAt(LocalDateTime.now())
			.build();
	}

	/**
	 * 락 소유자 확인
	 */
	public boolean isOwnedBy(String memberNo) {
		return this.memberNo != null && this.memberNo.equals(memberNo);
	}
}
