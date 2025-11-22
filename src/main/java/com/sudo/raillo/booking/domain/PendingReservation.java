package com.sudo.raillo.booking.domain;

import com.sudo.raillo.train.domain.ScheduleStop;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.RedisHash;

@RedisHash(value = "pendingReservation", timeToLive = 600) // 10분
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PendingReservation {

	@Id
	private String pendingId;  // UUID
	private String memberNo;
	private Long trainScheduleId;
	private List<Long> seatIds;
	private ScheduleStop departureStop;
	private ScheduleStop arrivalStop;
	private LocalDateTime createdAt;
}
