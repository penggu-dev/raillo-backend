package com.sudo.raillo.booking.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.redis.util.RedisKeyGenerator;
import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class BookingRedisRepositoryTest {

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private RedisTemplate<String, Object> customObjectRedisTemplate;

	@Autowired
	private RedisKeyGenerator redisKeyGenerator;

	private PendingBooking testPendingBooking;
	private List<PendingSeatBooking> testSeatBookings;
	private String testMemberNo;

	@BeforeEach
	void setUp() {
		testMemberNo = "202507300001";

		testSeatBookings = List.of(
			new PendingSeatBooking(1L, PassengerType.ADULT),
			new PendingSeatBooking(2L, PassengerType.CHILD)
		);

		testPendingBooking = PendingBooking.create(
			testMemberNo,
			100L,
			1L,
			2L,
			testSeatBookings,
			BigDecimal.valueOf(15000)
		);
	}

	@Test
	@DisplayName("PendingBooking 저장 및 조회에 성공한다")
	void save_and_getPendingBooking_success() {
		// when
		bookingRedisRepository.savePendingBooking(testPendingBooking);
		Optional<PendingBooking> optionalPendingBooking = bookingRedisRepository.getPendingBooking(testPendingBooking.getId());

		// then
		assertThat(optionalPendingBooking).isPresent();
		PendingBooking savedPendingBooking = optionalPendingBooking.get();

		// PendingBooking 역직렬화 확인
		assertThat(savedPendingBooking).isNotNull();
		assertThat(savedPendingBooking.getClass()).isEqualTo(PendingBooking.class);
		assertThat(savedPendingBooking.getId()).isEqualTo(testPendingBooking.getId());
		assertThat(savedPendingBooking.getMemberNo()).isEqualTo(testPendingBooking.getMemberNo());
		assertThat(savedPendingBooking.getTrainScheduleId()).isEqualTo(testPendingBooking.getTrainScheduleId());
		assertThat(savedPendingBooking.getDepartureStopId()).isEqualTo(testPendingBooking.getDepartureStopId());
		assertThat(savedPendingBooking.getArrivalStopId()).isEqualTo(testPendingBooking.getArrivalStopId());
		assertThat(savedPendingBooking.getTotalFare()).isEqualByComparingTo(testPendingBooking.getTotalFare());
		assertThat(savedPendingBooking.getCreatedAt()).isEqualTo(testPendingBooking.getCreatedAt());

		// PendingSeatBooking 역직렬화 확인
		assertThat(savedPendingBooking.getPendingSeatBookings()).hasSize(2);
		assertThat(savedPendingBooking.getPendingSeatBookings().get(0).getClass()).isEqualTo(PendingSeatBooking.class);
		assertThat(savedPendingBooking.getPendingSeatBookings().get(0).seatId()).isEqualTo(1L);
		assertThat(savedPendingBooking.getPendingSeatBookings().get(0).passengerType()).isEqualTo(PassengerType.ADULT);
		assertThat(savedPendingBooking.getPendingSeatBookings().get(1).seatId()).isEqualTo(2L);
		assertThat(savedPendingBooking.getPendingSeatBookings().get(1).passengerType()).isEqualTo(PassengerType.CHILD);
	}

	@Test
	@DisplayName("PendingBooking TTL이 10분(600초)으로 설정된다")
	void savePendingBooking_TTL_success() {
		// when
		bookingRedisRepository.savePendingBooking(testPendingBooking);

		// then
		String key = redisKeyGenerator.generatePendingBookingKey(testPendingBooking.getId());
		Long ttl = customObjectRedisTemplate.getExpire(key, TimeUnit.SECONDS);

		assertThat(ttl).isNotNull();
		assertThat(ttl).isBetween(580L, 600L); //약간의 오차 허용
	}

	@Test
	@DisplayName("PendingBookingMemberKey TTL이 580초로 설정된다")
	void savePendingBookingMemberKey_TTL_success() {
		// when
		bookingRedisRepository.savePendingBookingMemberKey(testPendingBooking.getId(), testMemberNo);

		// then
		String key = redisKeyGenerator.generatePendingBookingMemberKey(testMemberNo, testPendingBooking.getId());
		Long ttl = customObjectRedisTemplate.getExpire(key, TimeUnit.SECONDS);

		assertThat(ttl).isNotNull();
		assertThat(ttl).isBetween(570L, 580L); // 약간의 오차 허용
	}

	@Test
	@DisplayName("PendingBooking 삭제에 성공한다")
	void deletePendingBooking_success() {
		// given
		bookingRedisRepository.savePendingBooking(testPendingBooking);

		// when
		bookingRedisRepository.deletePendingBooking(testPendingBooking.getId());
		Optional<PendingBooking> savedPendingBooking = bookingRedisRepository.getPendingBooking(testPendingBooking.getId());

		// then
		assertThat(savedPendingBooking).isEmpty();
	}

	@Test
	@DisplayName("존재하지 않거나 만료된 PendingBooking 조회 시 empty Optional을 반환한다")
	void getPendingBooking_NotExists() {
		//given
		String nonExistId = "non-existing-id";

		// when
		Optional<PendingBooking> nonExistPendingBooking = bookingRedisRepository.getPendingBooking(nonExistId);

		// then
		assertThat(nonExistPendingBooking).isEmpty();
	}

	@Test
	@DisplayName("회원번호로 여러개의 PendingBooking 조회에 성공한다")
	void getPendingBookings_success() {
		// given
		bookingRedisRepository.savePendingBooking(testPendingBooking);
		bookingRedisRepository.savePendingBookingMemberKey(testPendingBooking.getId(), testMemberNo);

		PendingBooking anotherPendingBooking = PendingBooking.create(
			testMemberNo,
			200L,
			1L,
			2L,
			testSeatBookings,
			BigDecimal.valueOf(30000)
		);
		bookingRedisRepository.savePendingBooking(anotherPendingBooking);
		bookingRedisRepository.savePendingBookingMemberKey(anotherPendingBooking.getId(), testMemberNo);

		// when
		List<PendingBooking> pendingBookings = bookingRedisRepository.getPendingBookings(testMemberNo);

		// then
		assertThat(pendingBookings).hasSize(2);
		assertThat(pendingBookings)
			.extracting(PendingBooking::getId)
			.containsExactlyInAnyOrder(testPendingBooking.getId(), anotherPendingBooking.getId());
	}
}
