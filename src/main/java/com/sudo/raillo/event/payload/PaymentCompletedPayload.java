package com.sudo.raillo.event.payload;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 완료 이벤트 Payload
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PaymentCompletedPayload {

	private Long paymentId;
	private Long memberId;
	private Long tempBookingId;
	private String paymentKey;
	private int amount;
	private LocalDateTime paidAt;
	private List<Long> seatIds;

	public static PaymentCompletedPayload of(Long paymentId, Long memberId,
		Long tempBookingId, String paymentKey,
		int amount, LocalDateTime paidAt,
		List<Long> seatIds) {
		return new PaymentCompletedPayload(
			paymentId, memberId, tempBookingId,
			paymentKey, amount, paidAt, seatIds
		);
	}
}
