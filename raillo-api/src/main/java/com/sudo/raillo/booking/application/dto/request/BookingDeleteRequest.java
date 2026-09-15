package com.sudo.raillo.booking.application.dto.request;

import jakarta.validation.constraints.NotNull;

public record BookingDeleteRequest(
	@NotNull(message = "예매 ID는 필수입니다")
	Long bookingId
) {
}
