package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sudo.raillo.booking.cache.ReservationCacheKey;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.ReservationSeat;
import com.sudo.raillo.booking.domain.ReservationStop;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.support.annotation.RedisTest;
import com.sudo.raillo.train.domain.type.CarType;

@RedisTest
@DisplayName("ReservationRedisRepository - 예약 본문과 회원 인덱스")
class ReservationRedisRepositoryTest {

	private static final String MEMBER_NO = "202601010001";

	@Autowired
	private ReservationRedisRepository reservationRedisRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private RedisJsonConverter redisJsonConverter;

	private static Reservation reservation(String reservationId, long trainScheduleId) {
		return Reservation.create(
			reservationId, MEMBER_NO, trainScheduleId, 101, "KTX", LocalDate.of(2026, 10, 20),
			new ReservationStop(9001L, 0, 1L, "서울", LocalTime.of(6, 0)),
			new ReservationStop(9004L, 3, 5L, "부산", LocalTime.of(8, 52)),
			LocalDateTime.of(2026, 10, 20, 6, 0), CarType.STANDARD,
			List.of(new ReservationSeat(12L, 231L, 3, "12A", PassengerType.ADULT, new BigDecimal("59800"))),
			LocalDateTime.of(2026, 9, 17, 12, 0), Duration.ofMinutes(10));
	}

	private void store(Reservation reservation) {
		stringRedisTemplate.opsForValue().set(
			ReservationCacheKey.reservation(reservation.trainScheduleId(), reservation.reservationId()),
			redisJsonConverter.toJson(reservation));
	}

	@Test
	@DisplayName("회원 인덱스에 예약을 등록하면 field 값은 운행 ID이고 field에 TTL이 걸린다")
	void indexForMember() {
		// given
		String key = ReservationCacheKey.memberReservations(MEMBER_NO);

		// when
		reservationRedisRepository.indexForMember(MEMBER_NO, "RV1", 1001L, Duration.ofSeconds(300));

		// then
		assertThat(stringRedisTemplate.opsForHash().get(key, "RV1")).isEqualTo("1001");
		Duration fieldTtl = stringRedisTemplate.opsForHash()
			.getTimeToLive(key, TimeUnit.SECONDS, List.of("RV1"))
			.ttlOf("RV1");
		assertThat(fieldTtl.getSeconds()).isBetween(295L, 300L);
		assertThat(stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)).isEqualTo(-1);
	}

	@Test
	@DisplayName("인덱스에서 운행 ID를 찾을 때 없는 예약은 결과에서 빠진다")
	void findScheduleIds() {
		// given
		reservationRedisRepository.indexForMember(MEMBER_NO, "RV1", 1001L, Duration.ofMinutes(10));
		reservationRedisRepository.indexForMember(MEMBER_NO, "RV2", 1002L, Duration.ofMinutes(10));

		// when
		Map<String, Long> scheduleIds = reservationRedisRepository.findScheduleIds(MEMBER_NO, List.of("RV1", "RV9", "RV2"));

		// then
		assertThat(scheduleIds).containsExactly(Map.entry("RV1", 1001L), Map.entry("RV2", 1002L));
	}

	@Test
	@DisplayName("인덱스에서 제거한 예약은 더 이상 찾을 수 없다")
	void removeMemberIndex() {
		// given
		reservationRedisRepository.indexForMember(MEMBER_NO, "RV1", 1001L, Duration.ofMinutes(10));

		// when
		reservationRedisRepository.removeMemberIndex(MEMBER_NO, "RV1");

		// then
		assertThat(reservationRedisRepository.findScheduleIds(MEMBER_NO, List.of("RV1"))).isEmpty();
	}

	@Test
	@DisplayName("저장된 예약 JSON을 도메인 객체로 되돌린다")
	void find() {
		// given
		Reservation original = reservation("RV1", 1001L);
		store(original);

		// when
		Reservation found = reservationRedisRepository.find(1001L, "RV1").orElseThrow();

		// then
		assertThat(found).isEqualTo(original);
		assertThat(found.departure().stationName()).isEqualTo("서울");
		assertThat(found.seats().get(0).fare()).isEqualByComparingTo("59800");
	}

	@Test
	@DisplayName("여러 예약을 한 번에 읽을 때 만료된 예약은 결과에서 빠진다")
	void findAllSkipsMissing() {
		// given
		store(reservation("RV1", 1001L));
		store(reservation("RV2", 1002L));

		// when
		Map<String, Reservation> found = reservationRedisRepository.findAll(
			Map.of("RV1", 1001L, "RV2", 1002L, "RV9", 1003L));

		// then
		assertThat(found).containsOnlyKeys("RV1", "RV2");
		assertThat(found.get("RV2").trainScheduleId()).isEqualTo(1002L);
	}

	@Test
	@DisplayName("예약 JSON에는 Java 타입 메타데이터가 들어가지 않는다")
	void storesPlainJson() {
		// given
		Reservation reservation = reservation("RV1", 1001L);

		// when
		String json = redisJsonConverter.toJson(reservation);

		// then
		assertThat(json).doesNotContain("@class");
		assertThat(json).contains("\"operationDate\":\"2026-10-20\"");
		assertThat(json).contains("\"departureAt\":\"2026-10-20T06:00:00\"");
		assertThat(json).contains("\"time\":\"06:00:00\"");
	}
}
