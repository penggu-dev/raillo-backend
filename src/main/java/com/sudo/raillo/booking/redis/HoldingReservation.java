package com.sudo.raillo.booking.redis;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.TripType;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@RedisHash(value = "reservation", timeToLive = 600)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoldingReservation {

	@Id
	private Long id;

	private Long trainScheduleId;

	private Long memberId;

	private Long departureStopId;

	private Long arrivalStopId;

	private String reservationCode;

	private TripType tripType;

	private int totalPassengers;

	private String passengerSummary;

	private ReservationStatus reservationStatus;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime expiresAt;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime purchaseAt;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime cancelledAt;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime createdAt;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime updatedAt;

	private int fare;

	public void approve() {
		this.reservationStatus = ReservationStatus.PAID;
		this.purchaseAt = LocalDateTime.now();
		updateTimeStamp();
	}

	public void cancel() {
		this.reservationStatus = ReservationStatus.CANCELLED;
		this.cancelledAt = LocalDateTime.now();
		updateTimeStamp();
	}

	public void refund() {
		this.reservationStatus = ReservationStatus.REFUNDED;
		updateTimeStamp();
	}

	// 결제 가능 여부 확인
	public boolean canBePaid() {
		return this.reservationStatus.isPayable();
	}

	// 취소 가능 여부 확인
	public boolean canBeCancelled() {
		return this.reservationStatus.isCancellable();
	}

	// 환불 가능 여부 확인
	public boolean canBeRefunded() {
		return this.purchaseAt != null && this.reservationStatus.isRefundable();
	}

	private void updateTimeStamp() {
		this.updatedAt = LocalDateTime.now();
	}

}
